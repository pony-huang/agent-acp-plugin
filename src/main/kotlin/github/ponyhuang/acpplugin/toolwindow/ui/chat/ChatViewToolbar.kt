package github.ponyhuang.acpplugin.toolwindow.ui.chat

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.settings.AcpSettingsConfigurable
import github.ponyhuang.acpplugin.toolwindow.ToolWindowComposerState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ChatViewToolbar(
    private val project: Project,
    private val isLoading: () -> Boolean,
    private val isListingSessions: () -> Boolean = { false },
    private val hasSelectedAgent: () -> Boolean = { false },
    private val onNewSession: () -> Unit = {},
    private val onShowSessions: () -> Unit = {},
    private val onCancel: () -> Unit,
    private val isSessionConnected: () -> Boolean = { false },
    private val getComposerState: () -> ToolWindowComposerState = { ToolWindowComposerState.IDLE }
) : Disposable {
    private val newSessionAction = object : DumbAwareAction(
        MyBundle.message("toolbar.newSession"),
        MyBundle.message("toolbar.newSessionDescription"),
        AllIcons.General.Add
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            if (!isNewSessionActionEnabled()) {
                return
            }
            onNewSession()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = true
            e.presentation.isEnabled = isNewSessionActionEnabled()
        }
    }

    private val sessionsAction = object : DumbAwareAction(
        MyBundle.message("toolbar.sessions"),
        MyBundle.message("toolbar.sessionsDescription"),
        AllIcons.Actions.ListFiles
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            if (!isSessionActionEnabled()) {
                return
            }
            onShowSessions()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = true
            e.presentation.isEnabled = isSessionActionEnabled()
        }
    }

    private val settingsAction = object : DumbAwareAction(
        MyBundle.message("toolbar.settings"),
        MyBundle.message("toolbar.settingsDescription"),
        AllIcons.General.Settings
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AcpSettingsConfigurable::class.java)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = true
            e.presentation.isEnabled = true
        }
    }

    private val toolbarGroup = DefaultActionGroup().apply {
        add(newSessionAction)
        add(sessionsAction)
        add(settingsAction)
    }

    private val actionsToolbar = createActionsToolbar()
    private val statusIconLabel = JLabel()
    private val statusPanel = NonOpaquePanel(BorderLayout()).apply {
        name = "ChatToolbarStatusPanel"
        isVisible = false
        border = JBUI.Borders.emptyLeft(12)
        toolTipText = MyBundle.message("toolbar.connectionStatus")
        add(statusIconLabel, BorderLayout.CENTER)
    }
    private val rootPanel = JPanel(BorderLayout()).apply {
        add(actionsToolbar.component, BorderLayout.CENTER)
        add(statusPanel, BorderLayout.EAST)
    }

    init {
        actionsToolbar.targetComponent = rootPanel
    }

    val component: JComponent
        get() = rootPanel

    fun update() {
        runOnEdt {
            updateConnectionStatus()
            refreshActions()
            component.revalidate()
            component.repaint()
        }
    }

    fun updateConnectionStatus() {
        val state = getComposerState()
        // CONNECTING 状态才显示动画，SENDING 和 IDLE 都不显示
        if (state == ToolWindowComposerState.IDLE || state == ToolWindowComposerState.SENDING) {
            statusPanel.isVisible = false
            statusIconLabel.icon = null
            return
        }

        statusPanel.isVisible = true
        statusIconLabel.icon = AnimatedIcon.Default.INSTANCE
    }

    internal fun isStopActionEnabled(): Boolean = false

    internal fun isNewSessionActionEnabled(): Boolean = hasSelectedAgent() && !isLoading() && !isListingSessions()

    internal fun performNewSessionAction() {
        if (isNewSessionActionEnabled()) {
            onNewSession()
        }
    }

    internal fun isSessionActionEnabled(): Boolean = hasSelectedAgent() && !isLoading() && !isListingSessions()

    internal fun performSessionAction() {
        if (isSessionActionEnabled()) {
            onShowSessions()
        }
    }

    private fun refreshActions() {
        actionsToolbar.updateActionsAsync()
    }

    private fun createActionsToolbar(): ActionToolbar {
        return ActionManager.getInstance().createActionToolbar("AcpConversationToolbar", toolbarGroup, true).apply {
            component.border = JBEmptyBorder(5, 7, 5, 7)
            component.isOpaque = true
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application == null || application.isDispatchThread) {
            action()
        } else {
            application.invokeLater(action)
        }
    }

    override fun dispose() = Unit
}
