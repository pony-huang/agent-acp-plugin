package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.services.acp.PendingPermissionRequest
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JPanel

class PermissionPromptPanel : JPanel(BorderLayout()) {
    private val description = JBLabel(MyBundle.message("toolWindow.permission.auto"))

    init {
        add(description, BorderLayout.CENTER)
    }

    fun render(request: PendingPermissionRequest?) {
        description.text = if (request == null) {
            MyBundle.message("toolWindow.permission.auto")
        } else {
            "<html><b>${request.toolTitle}</b><br/>${request.options.joinToString("<br/>")}</html>"
        }
    }
}
