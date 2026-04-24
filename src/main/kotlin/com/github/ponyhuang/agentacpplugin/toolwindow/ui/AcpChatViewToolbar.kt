package com.github.ponyhuang.agentacpplugin.toolwindow.ui

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
    private val onShowSessions: () -> Unit = {},
    private val onCancel: () -> Unit
) : JPanel(), Disposable {

    val actionGroup = DefaultActionGroup()
    val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
        "AcpConversationToolbar",
        actionGroup,
        true
    )
    private val sessionsLoadingIndicator = JLabel(AnimatedIcon.Default.INSTANCE).apply {
        isVisible = false
        toolTipText = "Loading sessions..."
    }
    private val sessionsAction = object : DumbAwareAction("Sessions", "List and resume ACP sessions", AllIcons.Actions.ListFiles) {
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
        actionGroup.add(sessionsAction)
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        alignmentX = LEFT_ALIGNMENT
        toolbar.targetComponent = this
        add(toolbar.component)
        add(sessionsLoadingIndicator)
    }

    fun update() {
        runOnEdt {
            sessionsLoadingIndicator.isVisible = isListingSessions()
            toolbar.updateActionsAsync()
            revalidate()
            repaint()
        }
    }

    internal fun isStopActionEnabled(): Boolean = false

    internal fun performStopAction() = Unit

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
