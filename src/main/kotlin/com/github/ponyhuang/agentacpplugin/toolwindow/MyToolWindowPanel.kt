package com.github.ponyhuang.agentacpplugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.SwingConstants

class MyToolWindowPanel(project: Project) : JBPanel<MyToolWindowPanel>(BorderLayout()) {

    init {
        val label = JBLabel("ACP Tool Window", SwingConstants.CENTER).apply {
            foreground = JBColor.BLUE
        }
        add(label, BorderLayout.CENTER)

        val button = JButton("Click Me").apply {
            addActionListener {
                println("Button clicked!")
            }
        }
        add(button, BorderLayout.SOUTH)
    }
}
