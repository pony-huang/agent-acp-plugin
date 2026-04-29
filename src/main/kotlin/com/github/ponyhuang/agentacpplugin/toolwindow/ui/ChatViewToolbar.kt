package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.settings.AcpSettingsConfigurable
import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowComposerState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel

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
    private val connectionStatusIndicators = mutableListOf<JLabel>()

    val component: JComponent
        get() = toolbar.component

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
    private val connectionStatusAction = object : DumbAwareAction(), CustomComponentAction {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
            e.presentation.isVisible = true
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            return JLabel().apply {
                isVisible = false
                toolTipText = MyBundle.message("toolbar.connectionStatus")
                border = JBUI.Borders.empty(0, 4, 0, 2)
                connectionStatusIndicators += this
            }
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
        add(connectionStatusAction)
        add(settingsAction)
    }
    private val toolbar = createToolbar()

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
        val connected = isSessionConnected()

        val isVisible = when {
            state == ToolWindowComposerState.CONNECTING -> true
            state == ToolWindowComposerState.SENDING && connected -> true
            state == ToolWindowComposerState.IDLE && connected -> true
            else -> false
        }

        val icon = when {
            state == ToolWindowComposerState.CONNECTING -> AnimatedIcon.Default.INSTANCE
            state == ToolWindowComposerState.SENDING && connected -> AllIcons.Actions.Suspend
            state == ToolWindowComposerState.IDLE && connected -> AllIcons.Actions.Suspend
            else -> null
        }

        connectionStatusIndicators.forEach { indicator ->
            indicator.isVisible = isVisible
            indicator.icon = icon
        }
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
        toolbar.updateActionsAsync()
    }

    private fun createToolbar(): ActionToolbar {
        return ActionManager.getInstance().createActionToolbar("AcpConversationToolbar", toolbarGroup, true).apply {
            targetComponent = component
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
