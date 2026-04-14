package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowConversationItem
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * @author: pony
 * @date: Created in 18:22 2026/4/14
 */
class AcpConversationPanel(var project: Project) : ScrollablePanel() {

    private val messagePanel: JPanel
    private val componentsById = LinkedHashMap<String, JPanel>()

    init {
        layout = BorderLayout()
        border = EmptyBorder(8, 8, 8, 8)

        messagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        add(messagePanel, BorderLayout.CENTER)
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
    }
}

class MessageBubblePanel(
    title: String,
    content: String
) : BorderLayoutPanel() {

    private val editorPane: JEditorPane

    init {
        background = UIUtil.getPanelBackground()
        maximumSize = Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)
        isOpaque = false
        alignmentX = 0.0f
        editorPane = JEditorPane().apply {
            isEditable = false
            isOpaque = true
            background = UIUtil.getPanelBackground()
            foreground = UIUtil.getLabelForeground()
            val htmlEditorKit = HTMLEditorKitBuilder.simple()
            val styleSheet = htmlEditorKit.styleSheet
            styleSheet.addRule(
                """
                h1 { font-size: 14pt; margin-bottom: 2px; }
                h2 { font-size: 13pt; margin-bottom: 2px; }
                h3 { font-size: 12pt; margin-bottom: 2px; }
                h4 { font-size: 11pt; margin-bottom: 2px; }
            """.trimIndent()
            )
            editorKit = htmlEditorKit
        }
        editorPane.text = renderHtml(content)
        addToCenter(panel {
            row {
                cell(JBLabel(title).apply {
                    isOpaque = false
                    background = UIUtil.getEditorPaneBackground()
                }).align(AlignX.LEFT)
            }
            row {
                cell(editorPane).align(AlignX.LEFT)
            }
        }.apply {
            background = UIUtil.getPanelBackground()
            isOpaque = false
        })
    }
}

private fun ToolWindowConversationItem.createPanel(): JPanel {
    return when (this) {
        is ToolWindowConversationItem.UserText -> bubble(
            title = "You",
            content = text,
        )

        is ToolWindowConversationItem.AssistantText -> bubble(
            title = "Assistant",
            content = text,
        )

        is ToolWindowConversationItem.Thinking -> bubble(
            title = "Thinking",
            content = text,
        )

        is ToolWindowConversationItem.ToolCall -> bubble(
            title = buildString {
                append("Tool")
                append(": ")
                append(this@createPanel.title)
                status?.takeIf { it.isNotBlank() }?.let { append(" [$it]") }
            },
            content = details ?: "",
        )

        is ToolWindowConversationItem.Plan -> planPanel(this)
        is ToolWindowConversationItem.PermissionRequest -> permissionRequestPanel(this)
        is ToolWindowConversationItem.SystemStatus -> bubble(
            title = "Status",
            content = text,
        )

        is ToolWindowConversationItem.Error -> bubble(
            title = "Error",
            content = text,
        )
    }
}

private fun permissionRequestPanel(item: ToolWindowConversationItem.PermissionRequest): JPanel {
    val checkBoxes = linkedMapOf<String, JCheckBox>()
    val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    contentPanel.add(
        JLabel(
            "<html><body style='font-family: sans-serif; font-size: 11px; color: #666;'><b>Permission</b></body></html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
    )
    contentPanel.add(Box.createVerticalStrut(6))
    contentPanel.add(
        JLabel(
            "<html><body style='font-family: sans-serif; font-size: 13px;'>${item.title}</body></html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
    )
    contentPanel.add(Box.createVerticalStrut(8))

    item.options.forEach { option ->
        val checkBox = JCheckBox(option.label).apply {
            isOpaque = false
            isSelected = option.optionId == item.selectedOptionId
            isEnabled = !item.submitted
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener {
                if (!isSelected) {
                    isSelected = true
                    return@addActionListener
                }
                checkBoxes.forEach { (otherId, otherCheckBox) ->
                    if (otherId != option.optionId) {
                        otherCheckBox.isSelected = false
                    }
                }
            }
        }
        checkBoxes[option.optionId] = checkBox
        contentPanel.add(checkBox)
        contentPanel.add(Box.createVerticalStrut(4))
    }

    val submitButton = JButton(if (item.submitted) "Submitted" else "Submit").apply {
        isEnabled = !item.submitted && item.onSubmit != null
        alignmentX = Component.LEFT_ALIGNMENT
        addActionListener {
            val selectedOptionId =
                checkBoxes.entries.firstOrNull { it.value.isSelected }?.key ?: return@addActionListener
            item.onSubmit?.invoke(selectedOptionId)
        }
    }
    contentPanel.add(Box.createVerticalStrut(4))
    contentPanel.add(submitButton)

    return MessageBubblePanel(
        "", ""
    ).apply {
        add(contentPanel.apply {
            border = JBUI.Borders.empty()
        })
    }
}

private fun planPanel(item: ToolWindowConversationItem.Plan): JPanel {
    val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    contentPanel.add(
        JLabel(
            "<html><body style='font-family: sans-serif; font-size: 11px; color: #666;'><b>${item.title}</b></body></html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
    )
    contentPanel.add(Box.createVerticalStrut(8))

    item.entries.forEachIndexed { index, entry ->
        val currentStep = item.currentStep ?: item.entries.size
        val prefix = if (index == currentStep) "▶" else if (index < currentStep) "✓" else "○"
        val entryLabel = JLabel(
            "<html><body style='font-family: sans-serif; font-size: 12px; color: ${if (index == currentStep) "#0066cc" else "#333"};'>$prefix $entry</body></html>"
        ).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        contentPanel.add(entryLabel)
        contentPanel.add(Box.createVerticalStrut(4))
    }

    return MessageBubblePanel(
        "", ""
    ).apply {
        add(contentPanel.apply {
            border = JBUI.Borders.empty()
        })
    }
}


val flavour = GFMFlavourDescriptor()

private fun renderHtml(text: String): String {
    val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
    return HtmlGenerator(text, parsedTree, GFMFlavourDescriptor()).generateHtml()
}

private fun bubble(
    title: String,
    content: String,
): JPanel {
    return MessageBubblePanel(
        title = title,
        content = content,
    )
}
