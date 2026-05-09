package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project

class ToolWindowNotifier(
    private val project: Project
) : SessionNotificationSink {
    override fun notifyConnected(agentDisplayName: String) {
        notify(
            groupTitle = MyBundle.message("notification.acpConnection"),
            title = MyBundle.message("notification.connectedTo", agentDisplayName),
            content = MyBundle.message("notification.sessionEstablished"),
            type = NotificationType.INFORMATION
        )
    }

    override fun notifyDisconnected() {
        notify(
            groupTitle = MyBundle.message("notification.acpConnection"),
            title = MyBundle.message("notification.disconnected"),
            content = MyBundle.message("notification.sessionDisconnected"),
            type = NotificationType.INFORMATION
        )
    }

    override fun notifyNoAgentSelected() {
        notify(
            groupTitle = MyBundle.message("notification.acpSessions"),
            title = MyBundle.message("notification.noAgentSelected"),
            content = MyBundle.message("notification.selectAgentBeforeSession"),
            type = NotificationType.WARNING
        )
    }

    override fun notifyFailedListSessions(content: String) {
        notify(
            groupTitle = MyBundle.message("notification.acpSessions"),
            title = MyBundle.message("notification.failedListSessions"),
            content = content,
            type = NotificationType.ERROR
        )
    }

    override fun notifyNewSessionCreated(agentDisplayName: String) {
        notify(
            groupTitle = MyBundle.message("notification.acpSessions"),
            title = MyBundle.message("notification.newSessionCreated"),
            content = MyBundle.message("notification.sessionCreatedFor", agentDisplayName),
            type = NotificationType.INFORMATION
        )
    }

    override fun notifySessionResumed(session: AcpSessionService.SessionListItem) {
        notify(
            groupTitle = MyBundle.message("notification.acpSessions"),
            title = MyBundle.message("notification.sessionResumed"),
            content = MyBundle.message("notification.loadedSessionDetails", buildLoadedSessionNotificationContent(session)),
            type = NotificationType.INFORMATION
        )
    }

    override fun notifyError(groupTitle: String, title: String, content: String) {
        notify(
            groupTitle = groupTitle,
            title = title,
            content = content,
            type = NotificationType.ERROR
        )
    }

    internal fun buildLoadedSessionNotificationContent(
        session: AcpSessionService.SessionListItem
    ): String {
        val title = escapeNotificationText(
            session.title?.takeIf { it.isNotBlank() } ?: MyBundle.message("session.newSessionTitle")
        )
        val sessionId = escapeNotificationText(session.sessionId)
        return "<html>$title (sessionId: $sessionId)</html>"
    }

    private fun notify(groupTitle: String, title: String, content: String, type: NotificationType) {
        Notifications.Bus.notify(
            Notification(
                groupTitle,
                title,
                content,
                type
            ),
            project
        )
    }

    private fun escapeNotificationText(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

internal interface SessionNotificationSink {
    fun notifyConnected(agentDisplayName: String)
    fun notifyDisconnected()
    fun notifyNoAgentSelected()
    fun notifyFailedListSessions(content: String)
    fun notifyNewSessionCreated(agentDisplayName: String)
    fun notifySessionResumed(session: AcpSessionService.SessionListItem)
    fun notifyError(groupTitle: String, title: String, content: String)
}
