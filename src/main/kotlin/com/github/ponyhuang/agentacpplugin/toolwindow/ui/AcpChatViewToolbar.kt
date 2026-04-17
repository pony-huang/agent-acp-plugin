package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JPanel

class AcpChatViewToolbar(
    private val isLoading: () -> Boolean,
    private val onCancel: () -> Unit
) : JPanel(), Disposable {

    val actionGroup = DefaultActionGroup()
    val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
        "AcpConversationToolbar",
        actionGroup,
        true
    )

    init {
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        alignmentX = LEFT_ALIGNMENT
        toolbar.targetComponent = this
        add(toolbar.component)
    }

    fun update() {
        runOnEdt {
            toolbar.updateActionsAsync()
        }
    }

    internal fun isStopActionEnabled(): Boolean = false

    internal fun performStopAction() = Unit

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
