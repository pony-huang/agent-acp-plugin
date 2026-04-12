package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class UserInputPanel(
    private val onSubmit: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val inputArea = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = MyBundle.message("toolWindow.input.placeholder")
    }
    private val submitButton = JButton(MyBundle.message("toolWindow.send")).apply {
        addActionListener {
            val text = inputArea.text
            inputArea.text = ""
            onSubmit(text)
        }
    }

    init {
        add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(MyBundle.message("toolWindow.userInput"), JBScrollPane(inputArea))
                .addComponent(submitButton)
                .panel,
            BorderLayout.CENTER,
        )
    }

    fun setComposerEnabled(enabled: Boolean) {
        inputArea.isEnabled = enabled
        submitButton.isEnabled = enabled
    }
}
