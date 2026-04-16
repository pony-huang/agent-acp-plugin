package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class AcpConversationPanel(
    project: Project,
    parentDisposable: Disposable
) : ScrollablePanel(), Disposable {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionService = project.getService(AcpSessionService::class.java)
    private val messagePanel: JPanel

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8)
        background = UIUtil.getPanelBackground()

        messagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty()
            background = UIUtil.getPanelBackground()
        }

        add(messagePanel, BorderLayout.CENTER)
        bind()
        Disposer.register(parentDisposable, this)
    }

    private fun bind() {
        uiScope.launch {
            combine(sessionService.messages, sessionService.isLoading) { messages, isLoading ->
                messages to isLoading
            }.collectLatest { (messages, isLoading) ->
                render(messages, isLoading)
            }
        }
    }

    private fun render(messages: List<AcpSessionService.ChatMessage>, isLoading: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            val shouldStickToBottom = isNearBottom()

            messagePanel.removeAll()
            if (messages.isEmpty() && !isLoading) {
                messagePanel.add(createEmptyState())
            } else {
                messages.forEachIndexed { index, message ->
                    messagePanel.add(MessageCardPanel(message))
                    if (index != messages.lastIndex || isLoading) {
                        messagePanel.add(Box.createVerticalStrut(JBUI.scale(8)))
                    }
                }
                if (isLoading) {
                    messagePanel.add(createLoadingState())
                }
            }

            messagePanel.add(Box.createVerticalGlue())
            messagePanel.revalidate()
            messagePanel.repaint()

            if (shouldStickToBottom) {
                scrollToBottom()
            }
        }
    }

    private fun createEmptyState(): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(24, 8)
            add(
                JBLabel("Start a conversation to see ACP messages here.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.WEST
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun createLoadingState(): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(
                    JBColor.border(),
                    1, 1, 1, 1
                ),
                JBUI.Borders.empty(8, 10)
            )
            add(
                JBLabel("Thinking...").apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.WEST
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun isNearBottom(): Boolean {
        val scrollPane = enclosingScrollPane() ?: return true
        val scrollBar = scrollPane.verticalScrollBar
        return scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - JBUI.scale(24)
    }

    private fun scrollToBottom() {
        val scrollPane = enclosingScrollPane() ?: return
        val scrollBar = scrollPane.verticalScrollBar
        scrollBar.value = scrollBar.maximum
    }

    private fun enclosingScrollPane(): JScrollPane? {
        return SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane
    }

    override fun dispose() {
        uiScope.cancel()
    }
}

private class MessageCardPanel(
    private val message: AcpSessionService.ChatMessage
) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = backgroundForRole(message.role)
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(10)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        add(
            JBLabel(if (message.role == "user") "You" else "Assistant").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = LEFT_ALIGNMENT
            }
        )

        if (!message.thought.isNullOrBlank()) {
            add(ThoughtPanel(message.thought))
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        if (message.toolCalls.isNotEmpty()) {
            add(ToolCallListPanel(message.toolCalls))
            if (message.content.isNotBlank()) {
                add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }

        if (message.content.isNotBlank()) {
            add(MarkdownPane(message.content))
        }
    }

    private fun backgroundForRole(role: String): JBColor {
        val base = UIUtil.getPanelBackground()
        return if (role == "user") {
            JBColor(
                ColorUtil.mix(base, JBColor(0xD9ECFF, 0x25435D), 0.45),
                ColorUtil.mix(base, JBColor(0xD9ECFF, 0x25435D), 0.25)
            )
        } else {
            JBColor(
                ColorUtil.mix(base, JBColor(0xF3F5F7, 0x31363F), 0.85),
                ColorUtil.mix(base, JBColor(0xF3F5F7, 0x31363F), 0.85)
            )
        }
    }
}

private class ThoughtPanel(thought: String) : JPanel() {
    private val contentPanel = JPanel(BorderLayout())

    init {
        layout = BorderLayout(0, JBUI.scale(6))
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6, 8)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        val toggle = ActionLink("Show Thinking").apply {
            addActionListener {
                val expanded = !contentPanel.isVisible
                contentPanel.isVisible = expanded
                text = if (expanded) "Hide Thinking" else "Show Thinking"
                revalidate()
                repaint()
            }
        }
        add(toggle, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        contentPanel.isVisible = false
        contentPanel.add(MarkdownPane(thought), BorderLayout.CENTER)
        add(contentPanel, BorderLayout.CENTER)
    }
}

private class ToolCallListPanel(toolCalls: List<AcpSessionService.ToolCallInfo>) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        toolCalls.forEachIndexed { index, toolCall ->
            add(ToolCallRow(toolCall))
            if (index != toolCalls.lastIndex) {
                add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }
    }
}

private class ToolCallRow(toolCall: AcpSessionService.ToolCallInfo) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(statusColor(toolCall.status), 1, 3, 1, 1),
            JBUI.Borders.empty(6, 8)
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(
                    JBLabel("${iconForKind(toolCall.kind)} ${toolCall.title}").apply {
                        foreground = UIUtil.getLabelForeground()
                    },
                    BorderLayout.WEST
                )
                add(
                    JBLabel("${iconForStatus(toolCall.status)} ${toolCall.status}").apply {
                        foreground = statusColor(toolCall.status)
                    },
                    BorderLayout.EAST
                )
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        )

        val details = buildList {
            toolCall.locations.firstOrNull()?.let { add(it) }
            toolCall.contentSummary?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (details.isNotEmpty()) {
            add(Box.createVerticalStrut(JBUI.scale(4)))
            details.forEachIndexed { index, line ->
                add(
                    JBLabel(line).apply {
                        foreground = UIUtil.getContextHelpForeground()
                        border = if (index == 0) JBUI.Borders.empty() else JBUI.Borders.emptyTop(2)
                        alignmentX = LEFT_ALIGNMENT
                    }
                )
            }
        }
    }

    private fun statusColor(status: String): Color {
        return when (status) {
            "pending" -> JBColor(0xA15C00, 0xF2C46F)
            "in_progress" -> JBColor(0x0B65C2, 0x73B7FF)
            "completed" -> JBColor(0x2B7A0B, 0x6FCF5D)
            "failed" -> JBColor(0xC0392B, 0xFF8A80)
            else -> UIUtil.getContextHelpForeground()
        }
    }

    private fun iconForKind(kind: String?): String {
        return when (kind) {
            "read" -> "Read"
            "edit" -> "Edit"
            "delete" -> "Delete"
            "move" -> "Move"
            "search" -> "Search"
            "execute" -> "Run"
            "think" -> "Think"
            "fetch" -> "Fetch"
            "switch_mode" -> "Mode"
            else -> "Tool"
        }
    }

    private fun iconForStatus(status: String): String {
        return when (status) {
            "pending" -> "Queued"
            "in_progress" -> "Running"
            "completed" -> "Done"
            "failed" -> "Failed"
            else -> status
        }
    }
}

private class MarkdownPane(content: String) : JEditorPane() {
    init {
        isEditable = false
        isOpaque = false
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty()
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        editorKit = HTMLEditorKitBuilder.simple()
        val htmlEditorKit = editorKit as javax.swing.text.html.HTMLEditorKit
        htmlEditorKit.styleSheet.addRule(
            """
            body { font-family: sans-serif; font-size: 12px; color: #${ColorUtil.toHex(UIUtil.getLabelForeground())}; margin: 0; }
            p { margin: 0 0 6px 0; }
            pre { background: #${ColorUtil.toHex(UIUtil.getPanelBackground())}; padding: 6px; }
            code { font-family: monospace; }
            ul, ol { margin-top: 0; margin-bottom: 6px; }
            """.trimIndent()
        )
        text = renderHtml(content)
        caretPosition = 0
    }
}

private val markdownFlavour = GFMFlavourDescriptor()

private fun renderHtml(text: String): String {
    val parsedTree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(text)
    return HtmlGenerator(text, parsedTree, markdownFlavour).generateHtml()
}
