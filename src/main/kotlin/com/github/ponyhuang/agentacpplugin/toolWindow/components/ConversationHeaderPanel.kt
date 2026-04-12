package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.toolWindow.model.SessionHeaderViewModel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

class ConversationHeaderPanel : JPanel() {
    private val titleLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val modeLabel = JBLabel()
    private val usageLabel = JBLabel()

    init {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(MyBundle.message("toolWindow.chatConversation")), titleLabel)
            .addLabeledComponent(JBLabel(MyBundle.message("toolWindow.header.status")), statusLabel)
            .addLabeledComponent(JBLabel(MyBundle.message("toolWindow.header.mode")), modeLabel)
            .addLabeledComponent(JBLabel(MyBundle.message("toolWindow.header.usage")), usageLabel)
            .panel
        layout = java.awt.BorderLayout()
        add(panel, java.awt.BorderLayout.CENTER)
    }

    fun render(viewModel: SessionHeaderViewModel) {
        titleLabel.text = viewModel.title
        statusLabel.text = viewModel.statusText
        modeLabel.text = viewModel.modeText
        usageLabel.text = viewModel.usageText
    }
}
