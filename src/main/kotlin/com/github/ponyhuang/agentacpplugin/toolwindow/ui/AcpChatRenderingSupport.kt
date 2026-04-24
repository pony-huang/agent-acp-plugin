package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.model.StopReason
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.icons.AllIcons
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane

internal class MarkdownPane(content: String) : JEditorPane() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun getPreferredSize(): Dimension {
        val availableWidth = resolveAvailableWidth()
        if (availableWidth != null) {
            return Dimension(availableWidth, preferredHeightForWidth(availableWidth))
        }
        return super.getPreferredSize()
    }

    init {
        alignmentX = LEFT_ALIGNMENT
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty()
        editorKit = HTMLEditorKitBuilder.simple()
        updateContent(content)
    }

    fun updateContent(content: String) {
        text = renderHtml(content)
        caretPosition = 0
        revalidateParentChain()
        revalidate()
        repaint()
    }

    private fun resolveAvailableWidth(): Int? {
        var candidate: JComponent? = parent as? JComponent
        while (candidate != null) {
            val innerWidth = candidate.width - candidate.insets.left - candidate.insets.right
            if (innerWidth > 0) {
                return innerWidth.coerceAtLeast(1)
            }
            candidate = candidate.parent as? JComponent
        }
        return width.takeIf { it > 0 }
    }

    private fun preferredHeightForWidth(width: Int): Int {
        val previousSize = size
        super.setSize(width, Short.MAX_VALUE.toInt())
        val preferred = super.getPreferredSize()
        size = previousSize
        return preferred.height
    }

    private fun revalidateParentChain() {
        var current: JComponent? = parent as? JComponent
        while (current != null) {
            current.revalidate()
            current = current.parent as? JComponent
        }
    }
}

private val markdownFlavour = GFMFlavourDescriptor()

internal fun renderHtml(text: String): String {
    val parsedTree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(text)
    val body = HtmlGenerator(text, parsedTree, markdownFlavour).generateHtml()
    return """
        <html>
        <head>
        <style>
        body { margin: 0; padding: 0; }
        p { margin: 0 0 6px 0; }
        p:last-child { margin-bottom: 0; }
        ul, ol { margin-top: 0; margin-bottom: 0; padding-left: 18px; }
        pre { margin: 0; white-space: pre-wrap; }
        code { white-space: pre-wrap; }
        </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

internal fun toolKindDisplay(kind: String?): String {
    val emoji = when (kind) {
        "read" -> "\uD83D\uDCD6"
        "edit" -> "\u270F\uFE0F"
        "delete" -> "\uD83D\uDDD1\uFE0F"
        "move" -> "\uD83D\uDCE6"
        "search" -> "\uD83D\uDD0D"
        "execute" -> "\u25B6\uFE0F"
        "think" -> "\uD83E\uDDE0"
        "fetch" -> "\uD83C\uDF10"
        "switch_mode" -> "\uD83D\uDD00"
        else -> "\uD83D\uDD27"
    }
    return "$emoji ${kindLabel(kind)}"
}

private fun kindLabel(kind: String?): String {
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

internal fun statusIconFor(status: String): Icon {
    return when (status) {
        "pending" -> AllIcons.Process.Step_passive
        "in_progress" -> AllIcons.Process.Step_1
        "completed" -> AllIcons.General.InspectionsOK
        "cancelled" -> AllIcons.Actions.Cancel
        "failed" -> AllIcons.General.Error
        else -> AllIcons.General.Information
    }
}

internal fun String.toDisplayLabel(): String {
    return when (this) {
        "pending" -> "Queued"
        "in_progress" -> "Running"
        "completed" -> "Done"
        "cancelled" -> "Cancelled"
        "failed" -> "Failed"
        else -> replace('_', ' ')
    }
}

internal fun buildPermissionOptionLabel(option: AcpSessionService.PermissionOptionInfo): String {
    val parts = buildList {
        add(option.label)
        option.kind?.takeIf { it.isNotBlank() }?.let { add(it.replace('_', ' ')) }
    }
    return parts.joinToString(" • ")
}

internal fun messagePromptState(
    message: AcpSessionService.ChatMessage,
    latestAssistantMessageId: String?,
    isLoading: Boolean,
    lastStopReason: StopReason?
): MessagePromptState? {
    if (message.role != "assistant" || message.id != latestAssistantMessageId) {
        return null
    }
    if (isLoading) {
        return MessagePromptState.RUNNING
    }
    return when (lastStopReason) {
        null -> null
        StopReason.END_TURN -> MessagePromptState.COMPLETED
        StopReason.CANCELLED,
        StopReason.MAX_TOKENS,
        StopReason.MAX_TURN_REQUESTS,
        StopReason.REFUSAL -> MessagePromptState.WARNING
    }
}
