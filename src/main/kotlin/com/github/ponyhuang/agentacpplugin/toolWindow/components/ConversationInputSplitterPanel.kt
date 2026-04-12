package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.toolWindow.chat.ConversationTimelinePanel
import com.intellij.openapi.ui.Splitter
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

class ConversationInputSplitterPanel(
    val headerPanel: ConversationHeaderPanel,
    val timelinePanel: ConversationTimelinePanel,
    val inputPanel: UserInputPanel,
) : JPanel(BorderLayout()) {
    init {
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(headerPanel, BorderLayout.NORTH)
            add(timelinePanel, BorderLayout.CENTER)
        }
        val splitter = Splitter(true, 0.8f).apply {
            firstComponent = topPanel
            secondComponent = inputPanel
            dividerWidth = 8
            setHonorComponentsMinimumSize(true)
        }
        add(splitter, BorderLayout.CENTER)
    }
}
