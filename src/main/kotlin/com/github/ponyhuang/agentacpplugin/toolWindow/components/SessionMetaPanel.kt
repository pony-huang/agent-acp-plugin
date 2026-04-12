package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.toolWindow.model.SessionListViewModel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JPanel

class SessionMetaPanel : JPanel(BorderLayout()) {
    private val commandArea = JBTextArea(6, 24).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val configArea = JBTextArea(6, 24).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(MyBundle.message("toolWindow.availableCommands"), commandArea)
                .addLabeledComponent(MyBundle.message("toolWindow.configOptions"), configArea)
                .panel,
            BorderLayout.CENTER,
        )
    }

    fun render(viewModel: SessionListViewModel) {
        commandArea.text = if (viewModel.availableCommands.isEmpty()) {
            MyBundle.message("toolWindow.meta.empty")
        } else {
            viewModel.availableCommands.joinToString(separator = "\n")
        }
        configArea.text = if (viewModel.configOptions.isEmpty()) {
            MyBundle.message("toolWindow.meta.empty")
        } else {
            viewModel.configOptions.joinToString(separator = "\n")
        }
    }
}
