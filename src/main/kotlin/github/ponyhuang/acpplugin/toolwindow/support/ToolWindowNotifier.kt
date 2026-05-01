package github.ponyhuang.acpplugin.toolwindow.support

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project

internal class ToolWindowNotifier(
    private val project: Project
) {
    fun info(groupTitle: String, title: String, content: String) {
        notify(groupTitle, title, content, NotificationType.INFORMATION)
    }

    fun warning(groupTitle: String, title: String, content: String) {
        notify(groupTitle, title, content, NotificationType.WARNING)
    }

    fun error(groupTitle: String, title: String, content: String) {
        notify(groupTitle, title, content, NotificationType.ERROR)
    }

    private fun notify(groupTitle: String, title: String, content: String, type: NotificationType) {
        Notifications.Bus.notify(
            Notification(groupTitle, title, content, type),
            project
        )
    }
}
