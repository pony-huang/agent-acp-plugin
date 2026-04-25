package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowComposerState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

class AcpChatViewToolbar(
    private val isLoading: () -> Boolean,
    private val isListingSessions: () -> Boolean = { false },
    private val hasSelectedAgent: () -> Boolean = { false },
    private val onNewSession: () -> Unit = {},
    private val onShowSessions: () -> Unit = {},
    private val onCancel: () -> Unit,
    private val isSessionConnected: () -> Boolean = { false },
    private val getComposerState: () -> ToolWindowComposerState = { ToolWindowComposerState.IDLE }
) : JPanel(), Disposable {

    val actionGroup = DefaultActionGroup()
    val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
        "AcpConversationToolbar",
        actionGroup,
        true
    )
    private val sessionsLoadingIndicator = JLabel(AnimatedIcon.Default.INSTANCE).apply {
        isVisible = false
        toolTipText = MyBundle.message("toolbar.loadingSessions")
    }
    private val connectionStatusIndicator = JLabel().apply {
        isVisible = false
        toolTipText = MyBundle.message("toolbar.connectionStatus")
    }
    private val newSessionAction = object : DumbAwareAction(
        MyBundle.message("toolbar.newSession"),
        MyBundle.message("toolbar.newSessionDescription"),
        AllIcons.General.Add
    ) {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun displayTextInToolbar(): Boolean = true

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
        @Suppress("OVERRIDE_DEPRECATION")
        override fun displayTextInToolbar(): Boolean = true

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

    init {
        actionGroup.add(newSessionAction)
        actionGroup.add(sessionsAction)
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        alignmentX = LEFT_ALIGNMENT
        toolbar.targetComponent = this
        add(toolbar.component)
        add(sessionsLoadingIndicator)
        add(connectionStatusIndicator)
        @Suppress("DEPRECATION")
        toolbar.updateActionsImmediately()
    }

    fun update() {
        runOnEdt {
            sessionsLoadingIndicator.isVisible = isListingSessions()
            updateConnectionStatus()
            @Suppress("DEPRECATION")
            toolbar.updateActionsImmediately()
            revalidate()
            repaint()
        }
    }

    fun updateConnectionStatus() {
        val state = getComposerState()
        val connected = isSessionConnected()

        connectionStatusIndicator.isVisible = when {
            state == ToolWindowComposerState.CONNECTING -> true
            state == ToolWindowComposerState.SENDING && connected -> true
            state == ToolWindowComposerState.IDLE && connected -> true
            else -> false
        }

        connectionStatusIndicator.icon = when {
            state == ToolWindowComposerState.CONNECTING -> AnimatedIcon.Default.INSTANCE
            state == ToolWindowComposerState.SENDING && connected -> AllIcons.Actions.Suspend
            state == ToolWindowComposerState.IDLE && connected -> AllIcons.Actions.Suspend
            else -> null
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

    internal fun isSessionLoadingIndicatorVisible(): Boolean = sessionsLoadingIndicator.isVisible

    internal fun performSessionAction() {
        if (isSessionActionEnabled()) {
            onShowSessions()
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
