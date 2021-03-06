package com.hanhuy.android.keepshare

import android.app.AlertDialog
import android.os.Bundle
import android.view.{LayoutInflater, View, ViewGroup}
import com.hanhuy.android.conversions._
import com.hanhuy.android.extensions._
import com.hanhuy.android.common._
import com.hanhuy.android.keepshare.TypedResource._
import com.hanhuy.keepassj._
import Futures._

object GroupEditFragment {
  def edit(group: PwGroup) = {
    val f = new GroupEditFragment
    val b = new Bundle
    b.putString(BrowseActivity.EXTRA_GROUP_ID, group.getUuid.ToHexString)
    f.setArguments(b)
    f
  }
  def create(parent: PwGroup) = {
    val f = new GroupEditFragment
    val b = new Bundle
    f.setArguments(b)
    b.putString(BrowseActivity.EXTRA_GROUP_ID, parent.getUuid.ToHexString)
    b.putBoolean(EntryViewActivity.EXTRA_CREATE, true)
    f
  }
}
class GroupEditFragment extends AuthorizedFragment {
  setRetainInstance(true)
  var model: GroupEditModel = GroupEditModel.blank
  var baseModel = Option.empty[GroupEditModel]
  override def onCreateView(inflater: LayoutInflater, container: ViewGroup,
                            savedInstanceState: Bundle) = {
    val view = inflater.inflate(TR.layout.group_edit, container, false)

    val groupId = Option(getArguments) flatMap(a =>
      Option(a.getString(BrowseActivity.EXTRA_GROUP_ID)))

    val creating = Option(getArguments) map (
      _.getBoolean(EntryViewActivity.EXTRA_CREATE, false)) exists identity

    val group = view.findView(TR.edit_group)
    val title = view.findView(TR.edit_title)
    val notes = view.findView(TR.edit_notes)
    val iconObservable: Var[Int] = Var(title.icon)
    iconObservable.subscribe { icon =>
      model = model.copy(icon = icon)
      title.icon = icon
    }
    group.groupChange.subscribe { g =>
      model = model.copy(group = g.getUuid)
    }
    title.textfield.onTextChanged(s =>
      model = model.copy(title = s.? map (_.toString))
    )
    notes.textfield.onTextChanged(s =>
      model = model.copy(notes = s.? map (_.toString))
    )

    activity.database map { db =>
      groupId map { id =>
        val uuid = new PwUuid(KeyManager.bytes(id))
        db.getRootGroup.FindGroup(uuid, true)
      }
    } onSuccessMain { case g =>
      g foreach { grp =>
        if (creating) {
          group.group = grp
          view.findView(TR.delete).setVisibility(View.GONE)
          iconObservable() = Database.Icons(grp.getIconId.ordinal)
        } else {
          if (model == GroupEditModel.blank) {
            iconObservable() = Database.Icons(grp.getIconId.ordinal)
            title.text = grp.getName
            notes.text = grp.getNotes

            view.findView(TR.delete).onClick0 {
              if (EntryEditFragment.inRecycleBin(grp.getParentGroup)) {
                val t = getString(R.string.delete_name, grp.getName)
                new AlertDialog.Builder(activity)
                  .setTitle(t)
                  .setMessage(R.string.delete_permanently)
                  .setPositiveButton(android.R.string.ok, () => {
                    Database.delete(grp)
                    DatabaseSaveService.save()
                    activity.getFragmentManager.popBackStack()
                  })
                  .setNegativeButton(android.R.string.cancel, null)
                  .show()
              } else {
                val group = grp.getParentGroup
                Database.delete(grp)
                DatabaseSaveService.save()
                BrowseActivity.SnackbarSender.enqueue(getString(R.string.delete_entry, grp.getName), getString(R.string.undo)) { a =>
                  Database.recycleBin.foreach(_.getGroups.Remove(grp))
                  group.getGroups.Add(grp)
                  grp.setParentGroup(group)
                  grp.Touch(true, false)
                  DatabaseSaveService.save()
                  a.navigateTo(Option(group.getUuid))
                }
                activity.getFragmentManager.popBackStack()
              }
            }

            baseModel = Some(model)
          }
          if (grp.getParentGroup == null) {
            group.setVisibility(View.GONE)
            title.first = true
            view.findView(TR.delete).setVisibility(View.GONE)
          } else
            group.group = grp.getParentGroup
        }
      }
    }

    title.iconfield.onClick0 {
      EntryEditFragment.iconPicker(activity, title.iconfield, iconObservable.update)
    }
    view
  }
}

object GroupEditModel {
  def blank = GroupEditModel(R.mipmap.i00_password,
    None, None, PwUuid.Zero)
}
case class GroupEditModel(icon: Int, title: Option[String],
                          notes: Option[String], group: PwUuid) {
  def modified(other: GroupEditModel) =
    other.icon != icon || other.notes != notes || other.title != title
}
