package com.github.ponyhuang.agentacpplugin.toolWindow.chat

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

class PlanUpdatePanel(planText: String) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(6)
        add(JBTextArea(planText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
    }
}
