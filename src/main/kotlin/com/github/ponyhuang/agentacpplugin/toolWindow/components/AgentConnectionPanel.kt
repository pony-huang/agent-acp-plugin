package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.toolWindow.model.ConnectionStatusViewModel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class AgentConnectionPanel(
    private val onConnect: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val commandField = JBTextField().apply {
        text = MyBundle.message("toolWindow.connect.hint")
    }
    private val statusLabel = JLabel()
    private val connectButton = JButton(MyBundle.message("toolWindow.connect")).apply {
        addActionListener { onConnect(commandField.text) }
    }

    init {
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(MyBundle.message("toolWindow.connect.command"), commandField)
                .addComponent(connectButton)
                .addComponent(statusLabel)
                .panel,
            BorderLayout.CENTER,
        )
    }

    fun render(viewModel: ConnectionStatusViewModel) {
        if (commandField.text.isBlank()) {
            commandField.text = viewModel.commandLine
        }
        connectButton.isEnabled = viewModel.canConnect
        statusLabel.text = listOfNotNull(viewModel.statusText, viewModel.errorText).joinToString(" - ")
    }
}
