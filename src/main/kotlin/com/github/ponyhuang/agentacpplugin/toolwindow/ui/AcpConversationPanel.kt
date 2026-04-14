package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowConversationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.util.LinkedHashMap
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder

/**
 * @author: pony
 * @date: Created in 18:22 2026/4/14
 */
class AcpConversationPanel(var project: Project) : ScrollablePanel() {

    private val messagePanel: JPanel
    private val scrollPane: JBScrollPane
    private val componentsById = LinkedHashMap<String, JPanel>()

    init {
        layout = BorderLayout()
        border = EmptyBorder(8, 8, 8, 8)

        messagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(247, 247, 247)
        }

        scrollPane = JBScrollPane(messagePanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            background = Color(247, 247, 247)
        }

        add(scrollPane, BorderLayout.CENTER)
    }

    fun append(item: ToolWindowConversationItem) {
        val panel = item.createPanel()
        componentsById[item.itemId] = panel
        messagePanel.add(panel)
        refresh()
    }

    fun upsert(itemId: String, item: ToolWindowConversationItem) {
        val existingPanel = componentsById[itemId]
        if (existingPanel == null) {
            append(item)
            return
        }
        val index = messagePanel.components.indexOf(existingPanel)
        if (index < 0) {
            append(item)
            return
        }

        val updatedPanel = item.createPanel()
        componentsById[itemId] = updatedPanel
        messagePanel.remove(index)
        messagePanel.add(updatedPanel, index)
        refresh()
    }

    fun clear() {
        componentsById.clear()
        messagePanel.removeAll()
        refresh()
    }

    private fun refresh() {
        messagePanel.revalidate()
        messagePanel.repaint()
        javax.swing.SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }
}

class MessageBubblePanel(
    private val backgroundColor: Color,
    private val alignment: Int,
    private val maxWidthRatio: Double = 0.75,
    private val padding: Insets = Insets(10, 14, 10, 14)
) : JPanel(BorderLayout()) {

    init {
        background = Color(247, 247, 247)
        border = EmptyBorder(4, 8, 4, 8)
        maximumSize = Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)
    }

    override fun doLayout() {
        super.doLayout()
        // Apply max width constraint based on parent width
        val parentWidth = parent?.width ?: 600
        val maxWidth = (parentWidth * maxWidthRatio).toInt()
        for (component in components) {
            if (component.maximumSize.width > maxWidth) {
                component.maximumSize = Dimension(maxWidth, component.maximumSize.height)
            }
        }
    }

    override fun add(comp: Component): Component? {
        val wrapper = JPanel(FlowLayout(alignment, 0, 0))
        wrapper.background = backgroundColor
        wrapper.border = EmptyBorder(padding)
        wrapper.add(comp)
        return super.add(wrapper)
    }
}

private fun ToolWindowConversationItem.createPanel(): JPanel {
    return when (this) {
        is ToolWindowConversationItem.UserText -> bubble(
            backgroundColor = Color(219, 235, 253),
            alignment = FlowLayout.RIGHT,
            title = "You",
            content = text,
        )
        is ToolWindowConversationItem.AssistantText -> bubble(
            backgroundColor = Color(255, 255, 255),
            alignment = FlowLayout.LEFT,
            title = "Assistant",
            content = text,
        )
        is ToolWindowConversationItem.Thinking -> bubble(
            backgroundColor = Color(255, 253, 231),
            alignment = FlowLayout.LEFT,
            title = "Thinking",
            content = text,
        )
        is ToolWindowConversationItem.ToolCall -> bubble(
            backgroundColor = Color(243, 245, 249),
            alignment = FlowLayout.LEFT,
            title = buildString {
                append("Tool")
                append(": ")
                append(this@createPanel.title)
                status?.takeIf { it.isNotBlank() }?.let { append(" [$it]") }
            },
            content = details ?: "",
            monospace = true,
            maxWidthRatio = 0.85,
        )
        is ToolWindowConversationItem.SystemStatus -> bubble(
            backgroundColor = Color(240, 244, 255),
            alignment = FlowLayout.LEFT,
            title = "Status",
            content = text,
        )
        is ToolWindowConversationItem.Error -> bubble(
            backgroundColor = Color(255, 235, 238),
            alignment = FlowLayout.LEFT,
            title = "Error",
            content = text,
        )
    }
}

private fun bubble(
    backgroundColor: Color,
    alignment: Int,
    title: String,
    content: String,
    monospace: Boolean = false,
    maxWidthRatio: Double = 0.75,
): JPanel {
    val fontFamily = if (monospace) "monospace" else "sans-serif"
    return MessageBubblePanel(
        backgroundColor = backgroundColor,
        alignment = alignment,
        maxWidthRatio = maxWidthRatio,
    ).apply {
        add(
            JLabel(
                "<html><body style='font-family: sans-serif; font-size: 11px; color: #666;'><b>$title</b></body></html>"
            )
        )
        add(
            JLabel(
                "<html><body style='font-family: $fontFamily; font-size: 13px; max-width: 420px; line-height: 1.4;'>$content</body></html>"
            )
        )
    }
}
