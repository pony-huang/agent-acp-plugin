package com.github.ponyhuang.agentacpplugin.toolWindow.chat

import com.github.ponyhuang.agentacpplugin.toolWindow.model.TimelineItemViewModel
import com.github.ponyhuang.agentacpplugin.toolWindow.model.ToolCallViewModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

class ConversationTimelinePanel : JBPanel<ConversationTimelinePanel>(BorderLayout()) {
    private val contentPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    init {
        add(JBScrollPane(contentPanel), BorderLayout.CENTER)
    }

    fun render(items: List<TimelineItemViewModel>, bannerText: String?) {
        contentPanel.removeAll()
        bannerText?.let {
            contentPanel.add(JBLabel("<html><b>$it</b></html>").apply { border = JBUI.Borders.emptyBottom(8) })
        }
        if (items.isEmpty()) {
            contentPanel.add(JBLabel("No conversation yet"))
        } else {
            items.forEach { item ->
                contentPanel.add(createItemPanel(item))
            }
        }
        revalidate()
        repaint()
    }

    private fun createItemPanel(item: TimelineItemViewModel): JPanel {
        return when {
            item.type.startsWith("TOOL_") -> ToolCallPanel(ToolCallViewModel(item.title, item.state, item.body))
            item.title == "Plan" -> PlanUpdatePanel(item.body)
            else -> JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6)
                add(JBLabel("<html><b>${item.title}</b> <small>${item.state}</small></html>"), BorderLayout.NORTH)
                add(JBLabel("<html>${item.body.replace("\n", "<br/>")}</html>"), BorderLayout.CENTER)
                if (item.secondaryText.isNotBlank()) {
                    add(JBLabel("<html><small>${item.secondaryText}</small></html>"), BorderLayout.SOUTH)
                }
            }
        }
    }
}
