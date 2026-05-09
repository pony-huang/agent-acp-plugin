package github.ponyhuang.acpplugin.toolwindow.ui.toolcall

import github.ponyhuang.acpplugin.MyBundle
import com.intellij.icons.AllIcons
import javax.swing.Icon

internal fun toolKindDisplay(kind: String?): String {
    return kindLabel(kind)
}

internal fun toolKindIcon(kind: String?): Icon {
    return when (kind) {
        "read" -> AllIcons.Actions.MenuOpen
        "edit" -> AllIcons.Actions.Edit
        "delete" -> AllIcons.Actions.GC
        "move" -> AllIcons.Actions.MoveTo2
        "search" -> AllIcons.Actions.Search
        "execute" -> AllIcons.Actions.Execute
        "think" -> AllIcons.Actions.IntentionBulb
        "fetch" -> AllIcons.Nodes.PpWeb
        "switch_mode" -> AllIcons.Actions.ChangeView
        else -> AllIcons.General.GearPlain
    }
}

internal fun kindLabel(kind: String?): String {
    return when (kind) {
        "read" -> MyBundle.message("toolkind.read")
        "edit" -> MyBundle.message("toolkind.edit")
        "delete" -> MyBundle.message("toolkind.delete")
        "move" -> MyBundle.message("toolkind.move")
        "search" -> MyBundle.message("toolkind.search")
        "execute" -> MyBundle.message("toolkind.execute")
        "think" -> MyBundle.message("toolkind.think")
        "fetch" -> MyBundle.message("toolkind.fetch")
        "switch_mode" -> MyBundle.message("toolkind.switchMode")
        else -> MyBundle.message("toolkind.tool")
    }
}

internal fun statusIconFor(status: String): Icon {
    return when (status) {
        "pending" -> AllIcons.Process.Step_passive
        "in_progress" -> AllIcons.Process.Step_1
        "completed" -> AllIcons.General.InspectionsOK
        "cancelled" -> AllIcons.Actions.Cancel
        "failed" -> AllIcons.General.Error
        else -> AllIcons.General.Information
    }
}

internal fun String.toDisplayLabel(): String {
    return when (this) {
        "pending" -> MyBundle.message("status.queued")
        "in_progress" -> MyBundle.message("status.running")
        "completed" -> MyBundle.message("status.done")
        "cancelled" -> MyBundle.message("status.cancelled")
        "failed" -> MyBundle.message("status.failed")
        else -> replace('_', ' ')
    }
}
