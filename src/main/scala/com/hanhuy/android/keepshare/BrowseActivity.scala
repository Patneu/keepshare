package com.hanhuy.android.keepshare

import android.animation.{AnimatorListenerAdapter, Animator}
import android.annotation.TargetApi
import android.app.FragmentManager.OnBackStackChangedListener
import android.content.{Context, ComponentName, Intent}
import android.database.Cursor
import android.graphics.{Rect, Canvas, BitmapFactory}
import android.graphics.drawable.{BitmapDrawable, LayerDrawable}
import android.os.Bundle
import android.support.design.widget.{CoordinatorLayout, FloatingActionButton, Snackbar}
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.app.ActionBar
import android.support.v7.widget.RecyclerView.{State, ViewHolder}
import android.support.v7.widget.helper.ItemTouchHelper
import android.support.v7.widget.helper.ItemTouchHelper.SimpleCallback
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import com.hanhuy.android.conversions._
import com.hanhuy.android.extensions._
import com.hanhuy.android.common._

import android.app._
import android.view._
import android.widget._
import com.hanhuy.keepassj._
import rx.android.schedulers.AndroidSchedulers.mainThread
import rx.lang.scala.JavaConversions._
import rx.lang.scala.{Subscription, Observable, Subject}

import collection.JavaConverters._
import Futures._
import BrowseActivity._

import scala.concurrent.Future

import TypedResource._

/**
 * @author pfnguyen
 */
object BrowseActivity {

  @inline def animationEnd[A](@inline f: Animator => A): Animator.AnimatorListener = new AnimatorListenerAdapter {
    override def onAnimationEnd(animation: Animator) = f(animation)
  }

  val EXTRA_GROUP_ID = "keepshare.extra.GROUP_ID"
  val EXTRA_STACK = "keepshare.extra.STACK"
  def browse(a: Activity, group: PwGroup): Unit = {
    val intent = new Intent(a, classOf[BrowseActivity])
    intent.putExtra(BrowseActivity.EXTRA_GROUP_ID, KeyManager.hex(group.getUuid.getUuidBytes))
    a.startActivity(intent)
    PINHolderService.ping()
  }
  def open(a: Activity): Unit = {
    val intent = new Intent(a, classOf[BrowseActivity])
    a.startActivity(intent)
  }
  implicit val groupSort = new Ordering[PwGroup] {
    override def compare(x: PwGroup, y: PwGroup) =
      x.getName.compareToIgnoreCase(y.getName)
  }
  implicit val entrySort = new Ordering[PwEntry] {
    override def compare(x: PwEntry, y: PwEntry) =
      x.getStrings.ReadSafe(PwDefs.TitleField).compareToIgnoreCase(y.getStrings.ReadSafe(PwDefs.TitleField))
  }

  object SnackbarSender {
    private[this] var queue = Option.empty[(CharSequence, String, BrowseActivity => Any)]
    def show(activity: BrowseActivity, view: View): Unit = {
      queue foreach { case (msg, action, fn) =>
        val sb = Snackbar.make(view, msg, 10000)
        sb.setAction(action, () => fn(activity))
        sb.show()
      }
      queue = None
    }

    def enqueue[A](msg: CharSequence, action: String)(fn: BrowseActivity => A): Unit = {
      queue = Some((msg, action, fn))
    }
  }
}
class BrowseActivity extends AuthorizedActivity with TypedFindView with SwipeRefreshLayout.OnRefreshListener {
  lazy val list = findView(TR.recycler)
  lazy val refresher = findView(TR.refresher)
  private var searchView = Option.empty[SearchView]
  private var isEditing = false
  private var isCreating = false

  override def onDestroy() = {
    super.onDestroy()
  }

  lazy val editBar = getLayoutInflater.inflate(
    TR.layout.entry_edit_action_bar, null, false)

  override def onCreateOptionsMenu(menu: Menu) = {
    if (!isEditing) {
      super.onCreateOptionsMenu(menu)
      getMenuInflater.inflate(R.menu.browse, menu)
      searchView = Option(menu.findItem(R.id.menu_search).getActionView.asInstanceOf[SearchView])
      searchView foreach { search =>
        search.setIconifiedByDefault(getResources.getBoolean(R.bool.is_phone))
        search.setSearchableInfo(
          this.systemService[SearchManager].getSearchableInfo(
            new ComponentName(this, classOf[SearchableActivity])))
        search.setOnSuggestionListener(new SearchView.OnSuggestionListener {
          override def onSuggestionClick(i: Int) = {
            val c = search.getSuggestionsAdapter.getItem(i).asInstanceOf[Cursor]
            EntryViewActivity.show(BrowseActivity.this, c.getString(5))
            true
          }
          override def onSuggestionSelect(i: Int) = false
        })
      }

      Option(menu.findItem(R.id.database_sort)) foreach { m =>
        m.setChecked(settings.get(Settings.BROWSE_SORT_ALPHA))
      }

      if (Database.writeSupported) menu.findItem(R.id.edit_group).setVisible(true)

      database onSuccessMain { case d =>
        if (groupId.contains(d.getRecycleBinUuid) && Database.writeSupported) {
          menu.findItem(R.id.empty_recycle_bin).setVisible(true)
        }
      }
    }
    true
  }

  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.browse)

    val fab2 = findView(TR.fab2)
    findView(TR.fab_close) onClick0 findView(TR.fab_toolbar).hide()
    findView(TR.fab_toolbar).button = fab2
    findView(TR.fab_toolbar).container = findView(TR.container)

    findView(TR.create_entry) onClick0 {
      database.onSuccessMain { case db =>
        val root = db.getRootGroup

        EntryViewActivity.create(this,
          groupId map (root.FindGroup(_, true)) getOrElse root)
      }
    }
    findView(TR.create_group) onClick0 creating()
    findView(TR.group_edit) onClick0 editing(true)

    getSupportActionBar.setCustomView(editBar, new ActionBar.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT))
    if (!Database.writeSupported)
      fab2.setVisibility(View.GONE)
    refresher.setOnRefreshListener(this)

    editBar.findView(TR.cancel).onClick0 {
      updating(false, null)
    }
    editBar.findView(TR.save).onClick0 {
      val f = Option(getFragmentManager.findFragmentByTag("editor"))
      f foreach { case editor: GroupEditFragment =>
        def copyFromModel(e: PwGroup, needMove: Boolean): Unit = {
          editor.model.title foreach e.setName
          editor.model.notes foreach e.setNotes
          e.setIconId(PwIcon.values()(Database.Icons.indexOf(editor.model.icon)))

          if (needMove) {
            Option(e.getParentGroup) foreach (_.getGroups.Remove(e))
            Database.rootGroup foreach { root =>
              val group = root.FindGroup(editor.model.group, true)
              group.getGroups.Add(e)
              e.setParentGroup(group)
            }
          }
        }
        if (isCreating) {
          val e = new PwGroup(true, true)
          copyFromModel(e, true)
          navigateTo(Option(e.getUuid))
        } else {
          val needMove = editor.baseModel.exists(_.group != editor.model.group)
          ((for {
            root <- Database.rootGroup
            gid  <- groupId
          } yield root.FindGroup(gid, true)) orElse Database.rootGroup) foreach { g =>
            copyFromModel(g, needMove)
            g.Touch(true, false)
            navigateTo(Option(g.getUuid))
          }
        }
      }
      editing(false)
      DatabaseSaveService.save()
    }

    getFragmentManager.addOnBackStackChangedListener(new OnBackStackChangedListener {
      override def onBackStackChanged() = {
        if (!Option(getFragmentManager.findFragmentByTag("editor")).exists(_.isVisible)) {
          editing(false)
          if (!isCreating)
            navigateTo(groupId)
          SnackbarSender.show(BrowseActivity.this, list)
        }
      }
    })
  }

  private[this] var refreshDialog = Option.empty[Dialog]
  override def onRefresh() = {
    // because side-effects OP
    var sub: Subscription = null
    sub = DatabaseSaveService.saving.observeOn(mainThread).subscribe(b => {
      if (b) {
        refreshDialog = Some(showingDialog(ProgressDialog.show(this,
          getString(R.string.saving_database), getString(R.string.please_wait),
          true, false)))
      } else {
        refreshDialog foreach dismissDialog
        sub.unsubscribe()
        Database.close()
        database onSuccessMain { case db =>
          refresher.setRefreshing(false)
          navigateTo(groupId)
        }
        database onFailureMain { case t =>
          refresher.setRefreshing(false)
          Toast.makeText(this, "Unable to reload database: " + t.getMessage, Toast.LENGTH_LONG).show()
          finish()
        }
      }
    })
  }

  override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
    case android.R.id.home =>
      onBackPressed()
      true
    case R.id.edit_group =>
      editing(true)
      true
    case R.id.empty_recycle_bin =>
      Database.emptyRecycleBin()
      list.getAdapter.notifyDataSetChanged()
      DatabaseSaveService.save()
      true
    case R.id.database_sort =>
      settings.set(Settings.BROWSE_SORT_ALPHA, !item.isChecked)
      item.setChecked(!item.isChecked)
      Option(list.getAdapter).foreach(_.notifyDataSetChanged())
      true

    case _ => super.onOptionsItemSelected(item)
  }

  override def onNewIntent(intent: Intent) = {
    super.onNewIntent(intent)
    setIntent(intent)
  }


  override def onBackPressed() = {
//    navigateUp()
    if (isEditing) {
      new AlertDialog.Builder(this)
        .setTitle(R.string.cancel)
        .setMessage(R.string.discard_confirm)
        .setNegativeButton(R.string.discard, () => {
          editing(false)
        })
        .setPositiveButton(R.string.keep_editing, null)
        .show()
    } else {
      val shouldBack = searchView.exists(_.isIconified) ||
        getResources.getBoolean(R.bool.is_tablet)
      searchView foreach (_.setIconified(true))
      if (shouldBack) {
        super.onBackPressed()
        if (Option(getIntent) exists (_.hasExtra(EXTRA_GROUP_ID)))
          overridePendingTransition(0, 0)
      }
    }
  }

  private def navigateUp(): Unit = {
//    stack match {
//      case x :: xs =>
//        stack = xs
//        navigateTo(xs.headOption)
//      case Nil =>
//        Option(getIntent) foreach { _.putExtra(EXTRA_GROUP_ID, null: String) }
//        finish()
//    }
//    for {
//      intent <- Option(getIntent)
//      extras <- Option(intent.getExtras)
//      head   <- stack.headOption
//    } {
//      intent.putExtra(EXTRA_GROUP_ID, head.ToHexString)
//    }
  }

  def navigateTo(groupId: Option[PwUuid]): Unit = {
    database flatMap { db =>
      if (db.IsOpen) Future.successful(db) else {
        Future.failed(KeyError.NeedLoad)
      }
    } onSuccessMain { case db =>
      val root = db.getRootGroup
      val group = groupId flatMap { id =>
        Option(root.FindGroup(id, true)) } getOrElse root
      val ab = getSupportActionBar
      ab.setTitle(group.getName)
//      if (PwUuid.Zero == group.getCustomIconUuid) {
        val bm = BitmapFactory.decodeResource(getResources, Database.Icons(group.getIconId.ordinal))
        val bd = new BitmapDrawable(getResources, bm)
        bd.setGravity(Gravity.CENTER)
        val layers = new LayerDrawable(Array(bd, getResources.getDrawable(R.drawable.logo_frame)))
        ab.setIcon(layers)
        ab.setDisplayShowHomeEnabled(true)
        ab.setDisplayHomeAsUpEnabled(group != root)
//      }
      val groups = group.GetGroups(false).asScala.toList
      val entries = group.GetEntries(false).asScala.toList

      val adapter = new GroupAdapter(db, Option(group.getParentGroup), groups, entries)
      list.setLayoutManager(new LinearLayoutManager(this))
      list.setAdapter(adapter)
      val callback = new SimpleCallback(0, ItemTouchHelper.RIGHT) {
        override def onSwiped(viewHolder: ViewHolder, direction: Int) = {
          val gh = viewHolder.asInstanceOf[GroupHolder]
          gh.upper.animate().x(viewHolder.itemView.getRight).alpha(0).start()
          gh.item.foreach { item =>
            val pos = viewHolder.getAdapterPosition
            val (inRecycle, title) = item.fold({ g =>
              adapter.groups = adapter.groups filterNot (_ == g)
              val inRecycle = Database.recycleBin.exists(g.IsContainedIn)
              Database.delete(g)
              (inRecycle, g.getName)
            }, { e =>
              adapter.entries = adapter.entries filterNot (_ == e)
              val inRecycle = (for {
                p <- Option(e.getParentGroup)
                r <- Database.recycleBin
              } yield {
                p == r || p.IsContainedIn(r)
              }) getOrElse false
              Database.delete(e)
              (inRecycle, e.getStrings.ReadSafe(PwDefs.TitleField))
            })
            DatabaseSaveService.save()
            adapter.data = adapter.sortedData
            adapter.notifyItemRemoved(pos)
            val sb = Snackbar.make(viewHolder.itemView,
              getString(R.string.delete_entry, title), 5000)
            if (!inRecycle) sb.setAction(R.string.undo, () => {
              item.fold({ g =>
                adapter.groups  = g :: adapter.groups
                Database.recycleBin.foreach(_.getGroups.Remove(g))
                group.getGroups.Add(g)
                g.setParentGroup(group)
                g.Touch(true, false)
              }, { e =>
                adapter.entries = e :: adapter.entries
                Database.recycleBin.foreach(_.getEntries.Remove(e))
                group.getEntries.Add(e)
                e.setParentGroup(group)
                e.Touch(true, false)
              })
              DatabaseSaveService.save()
              adapter.data = adapter.sortedData
              adapter.notifyItemInserted(adapter.data.indexOf(item))
            })
            sb.show()
          }
        }

        override def onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder) =
          false

        override def onChildDraw(c: Canvas, recyclerView: RecyclerView,
                                 viewHolder: ViewHolder, dX: Float, dY: Float,
                                 actionState: Int, isCurrentlyActive: Boolean) = {
          val width = recyclerView.getWidth
          val alpha = dX.toFloat / width
          val upper = viewHolder.asInstanceOf[GroupHolder].upper
          upper.setTranslationX(dX)
          upper.setAlpha(1-alpha)
        }

        override def getSwipeThreshold(viewHolder: ViewHolder) = 0.5f

        override def getSwipeDirs(recyclerView: RecyclerView, viewHolder: ViewHolder) = {
          val gh = viewHolder.asInstanceOf[GroupHolder]
          val isRecycle = (for {
            i <- gh.item
            rid <- Database.recycleBinId
          } yield {
            i.left.exists(_.getUuid == rid)
          }) getOrElse false
          if (gh.isUp || isRecycle) 0 else ItemTouchHelper.RIGHT
        }
      }
      if (Database.writeSupported && !Database.recycleBinId.contains(group.getUuid))
        new ItemTouchHelper(callback).attachToRecyclerView(list)
      list.setNestedScrollingEnabled(true)
    }
    if (ready) database onFailureMain { case e =>
      Toast.makeText(this, "Failed to load database: " + e.getMessage,
        Toast.LENGTH_LONG).show()
    }
  }

//  private var stack = List.empty[PwUuid]

  def groupId = for {
    intent <- Option(getIntent)
    id     <- Option(intent.getStringExtra(BrowseActivity.EXTRA_GROUP_ID))
  } yield new PwUuid(KeyManager.bytes(id))

  private def handleIntent(): Unit = {

//    for {
//      id   <- groupId
//      head <- stack.headOption orElse Some(PwUuid.Zero)
//      root <- Database.rootGroupid orElse Some(PwUuid.Zero)
//    } {
//      if (head != id && id != root)
//        stack = id :: stack
//    }

//    navigateTo(stack.headOption)
    navigateTo(groupId)
  }

  override def onStart() = {
    super.onStart()
  }

  override def onResume() = {
    super.onResume()
    if (!isEditing) {
      handleIntent()
      SnackbarSender.show(this, list)
    }
  }

  def creating(): Unit = {
    database.map { db =>
      val root = db.getRootGroup
      groupId flatMap { id =>
        Option(root.FindGroup(id, true)) } getOrElse root
    }.onSuccessMain { case group =>
      updating(true, GroupEditFragment.create(group))
      isCreating = true
      editBar.findView(TR.title).setText("Create group")
    }
  }
  def editing(b: Boolean): Unit = {
    database.map { db =>
      val root = db.getRootGroup
      groupId flatMap { id =>
        Option(root.FindGroup(id, true)) } getOrElse root
    }.onSuccessMain { case group =>
      updating(b, if (b) GroupEditFragment.edit(group) else null)
      editBar.findView(TR.title).setText("Update group")
    }
  }

  def updating(b: Boolean, f: Fragment) {
    getSupportActionBar.setHomeButtonEnabled(!b)
    getSupportActionBar.setDisplayShowHomeEnabled(!b)
    getSupportActionBar.setDisplayHomeAsUpEnabled(!b)
    getSupportActionBar.setDisplayShowTitleEnabled(!b)
    getSupportActionBar.setDisplayShowCustomEnabled(b)
    if (b) {
      editBar.getParent match {
        case t: Toolbar => t.setContentInsetsAbsolute(0, 0)
        case _ =>
      }
      findView(TR.fab2).hide()
      if (getFragmentManager.findFragmentByTag("editor") == null)
        getFragmentManager.beginTransaction()
          .add(R.id.content, f, "editor")
          .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
          .addToBackStack("edit")
          .commit()
    } else {
      isCreating = false
      findView(TR.fab2).show()
      getFragmentManager.popBackStack()
    }
    isEditing = b
    invalidateOptionsMenu()
  }

  override def onSaveInstanceState(outState: Bundle) = {
    super.onSaveInstanceState(outState)
//    outState.putStringArray(EXTRA_STACK,
//      stack.map (u => u.ToHexString()).toArray)
  }

  override def onRestoreInstanceState(savedInstanceState: Bundle) = {
    super.onRestoreInstanceState(savedInstanceState)
//    Option(savedInstanceState.getStringArray(EXTRA_STACK)) foreach { ss =>
//      stack = ss map (s => new PwUuid(KeyManager.bytes(s))) toList
//    }
  }

  case class GroupHolder(view: ViewGroup, parent: Option[PwGroup], db: PwDatabase) extends RecyclerView.ViewHolder(view) {
    private[this] var _item = Option.empty[Either[PwGroup,PwEntry]]
    private[this] var upItem = false
    def item = _item
    def isUp = upItem
    lazy val upper = view.getChildAt(1)
    lazy val name = view.findView(TR.name)
    lazy val folder_image = view.findView(TR.folder_image)
    lazy val entry_image = view.findView(TR.entry_image)

    def bind(item: Either[PwGroup,PwEntry]): Unit = {
      _item = Some(item)
      upItem = false
      name.setText(item.fold(_.getName, _.getStrings.ReadSafe(PwDefs.TitleField)))

      item.left foreach { group =>
        folder_image.setImageResource(if (db.getRecycleBinUuid.Equals(group.getUuid))
          R.drawable.ic_delete_black_24dp else R.drawable.ic_folder_open_black_24dp)
        if (parent exists (_.getUuid.equals(group.getUuid))) {
          folder_image.setImageResource(R.drawable.ic_expand_less_black_24dp)
          upItem = true
        }
        folder_image.setVisibility(View.VISIBLE)
        //        if (PwUuid.Zero == group.getCustomIconUuid)
        entry_image.setImageResource(Database.Icons(group.getIconId.ordinal))
      }
      item.right foreach { entry =>
        folder_image.setVisibility(View.INVISIBLE)
        //        if (PwUuid.Zero == entry.getCustomIconUuid)
        entry_image.setImageResource(Database.Icons(entry.getIconId.ordinal))
      }
      view.onClick0 {
        view.setActivated(true)
        import iota.std.Configurations._
        implicit val c = BrowseActivity.this
        item.left foreach { grp =>
          browse(BrowseActivity.this, grp)
          overridePendingTransition(0, 0)
        }
        item.right foreach { entry =>
          EntryViewActivity.show(BrowseActivity.this, entry)
          overridePendingTransition(R.anim.slide_in_right,
            R.anim.slide_out_left)
        }
      }
    }
  }
  class GroupAdapter(db: PwDatabase, parent: Option[PwGroup], _groups: List[PwGroup], _entries: List[PwEntry]) extends RecyclerView.Adapter[GroupHolder] {
    import TypedResource._
    var groups = _groups
    var entries = _entries
    var data = sortedData

    override def getItemCount = data.size


    override def onCreateViewHolder(viewGroup: ViewGroup, i: Int) = {
      GroupHolder(getLayoutInflater.inflate(TR.layout.browse_pwgroup_item, viewGroup, false), parent, db)
    }

    override def getItemId(position: Int) =
      data(position).fold(Database.getId, Database.getId)

    override def onBindViewHolder(vh: GroupHolder, i: Int) = vh.bind(data(i))

    registerAdapterDataObserver(new RecyclerView.AdapterDataObserver {
      override def onChanged() = data = sortedData
      override def onItemRangeRemoved(positionStart: Int, itemCount: Int) = data = sortedData
    })

    def sortedData: Vector[Either[PwGroup,PwEntry]] = {
      (parent map Left.apply).toVector ++ (if (settings.get(Settings.BROWSE_SORT_ALPHA)) {
        (groups.sorted map (Left(_))) ++ (entries.sorted map (Right(_)))
      } else {
        (groups map (Left(_))) ++ (entries map (Right(_)))
      })
    }
  }
}

class FabToolbar(val context: Context, attrs: AttributeSet) extends FrameLayout(context, attrs) with iota.HasContext {
  import iota.std.Contexts._
  lazy val screenWidth = getResources.getDisplayMetrics.widthPixels

  private[this] var showing = false
  private[this] var _button: ObservableFab = _
  def button = _button
  def button_=(b: ObservableFab) = {
    _button = b
    if (container != null)
      container.setBackgroundColor(iota.resolveAttr(R.attr.colorAccent, _.data))
    b onClick0 show()
    b.visibility.observeOn(mainThread).subscribe(b => if (b && showing) hide())
  }

  private[this] var _container: ViewGroup = _
  def container = _container
  def container_=(b: ViewGroup) = {
    _container = b
    if (button != null)
      container.setBackgroundColor(iota.resolveAttr(R.attr.colorAccent, _.data))
  }

  def show(): Unit = {
    container.setVisibility(View.VISIBLE)
    showing = true
    button.hide()
    if (iota.v(21))
      animate(0, screenWidth, null)
    else {
      val h = container.getHeight
      val anim = if (h == 0) {
        container.setAlpha(0.0f)
        container.animate().alpha(1.0f)
      } else {
        container.animate().yBy(-container.getHeight)
      }
      anim.setListener(null).start()
    }
  }

  def hide(): Unit = {
    showing = false
    if (iota.v(21))
      animate(screenWidth, 0, closeListener)
    else {
      container.getHeight
      val xy = Array.ofDim[Int](2)
      container.getLocationOnScreen(xy)
      container.animate().yBy(container.getHeight).setListener(animationEnd {_ =>
        container.setVisibility(View.GONE)
        button.show()
      }).start()
    }
  }

  @TargetApi(21)
  def animate(sr: Float, er: Float, listener: Animator.AnimatorListener) {
    val start = math.max(sr, math.abs(button.getTop - button.getBottom)) / 2
    val onscreen = Array.ofDim[Int](2)
    container.getLocationOnScreen(onscreen)
    val cx = (button.getLeft + button.getRight) / 2 - onscreen(0)
    val cy = (button.getTop + button.getBottom) / 2 - onscreen(1)

    val animator = ViewAnimationUtils.createCircularReveal(container, cx, cy, start, er)
    animator.setInterpolator(new AccelerateDecelerateInterpolator)
    animator.setDuration(250)
    if (listener != null) {
      animator.addListener(listener)
    }
    animator.start()
  }

  val closeListener = animationEnd { _ =>
    container.setVisibility(View.GONE)
    button.show()
  }
}

class ObservableFab(c: Context, attrs: AttributeSet) extends FloatingActionButton(c, attrs) {
  private[this] val _vis: Subject[Boolean] = Subject()
  def visibility: Observable[Boolean] = _vis

  override def show() = {
    super.show()
    _vis.onNext(true)
  }

  override def hide() = {
    super.hide()
    _vis.onNext(false)
  }
}

class HideFabBehavior(context: Context, attrs: AttributeSet) extends CoordinatorLayout.Behavior[ViewGroup](context, attrs) {
  private[this] var fab = Option.empty[FloatingActionButton]
  private[this] var scrolling = false

  override def layoutDependsOn(parent: CoordinatorLayout, child: ViewGroup, dependency: View) = {
    dependency match {
      case f: FloatingActionButton => fab = Some(f)
      case _ =>
    }
    false
  }

  override def onNestedScroll(coordinatorLayout: CoordinatorLayout, child: ViewGroup, target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) =
    if (Database.writeSupported) {
      fab.foreach { f =>
        if (dyConsumed <= 0 && dyUnconsumed == 0) f.show()
        else if (dyUnconsumed != 0) {
          if (scrolling) {
            if (f.isShown) f.hide() else f.show()
            scrolling = false
          }
        } else {
          f.hide()
        }
      }
    }

  override def onStartNestedScroll(coordinatorLayout: CoordinatorLayout, child: ViewGroup, directTargetChild: View, target: View, nestedScrollAxes: Int) = {
    scrolling = true
    true
  }
}
