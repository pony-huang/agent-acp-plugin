package com.github.ponyhuang.agentacpplugin.toolWindow.chat

import com.github.ponyhuang.agentacpplugin.toolWindow.model.ToolCallViewModel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

class ToolCallPanel(viewModel: ToolCallViewModel) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(6)
        add(JBLabel("${viewModel.title} (${viewModel.status})"), BorderLayout.NORTH)
        add(JBLabel("<html>${viewModel.summary.replace("\n", "<br/>")}</html>"), BorderLayout.CENTER)
    }
}
