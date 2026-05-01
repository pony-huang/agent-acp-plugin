package github.ponyhuang.acpplugin.toolwindow.ui.chat

import github.ponyhuang.acpplugin.services.AcpSessionService
import javax.swing.JComponent

internal sealed interface MessageEntryView {
    val component: JComponent
    fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean
    fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean)
    fun dispose() {}
}

internal class MarkdownEntryView(
    private var currentText: String,
    private val markdownPane: MarkdownPane
) : MessageEntryView {
    override val component: JComponent
        get() = markdownPane

    constructor(text: String) : this(text, MarkdownPane(text))

    override fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean =
        entry is AcpSessionService.MessageEntry.Content

    override fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean) {
        entry as AcpSessionService.MessageEntry.Content
        currentText = entry.text
        markdownPane.updateContent(entry.text)
    }
}

internal class ThoughtEntryView(
    private val thoughtPanel: ThoughtPanel,
    private var onThoughtToggled: (Boolean) -> Unit
) : MessageEntryView {
    override val component: JComponent
        get() = thoughtPanel

    override fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean =
        entry is AcpSessionService.MessageEntry.Thought

    override fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean) {
        entry as AcpSessionService.MessageEntry.Thought
        thoughtPanel.update(entry.text, thoughtExpanded, onThoughtToggled)
    }
}

internal class ToolCallEntryView(private val toolCallRow: ToolCallRow) : MessageEntryView {
    override val component: JComponent
        get() = toolCallRow

    override fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean =
        entry is AcpSessionService.MessageEntry.ToolCall

    override fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean) {
        entry as AcpSessionService.MessageEntry.ToolCall
        toolCallRow.update(entry.toolCall)
    }

    override fun dispose() {
        toolCallRow.dispose()
    }
}

internal class PermissionRequestEntryView(
    private var requestId: String,
    val card: PermissionRequestCardPanel
) : MessageEntryView {
    val currentRequestId: String
        get() = requestId

    override val component: JComponent
        get() = card

    override fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean =
        entry is AcpSessionService.MessageEntry.PermissionRequest &&
            entry.request.requestId == requestId

    override fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean) {
        entry as AcpSessionService.MessageEntry.PermissionRequest
        requestId = entry.request.requestId
        card.updateRequest(entry.request)
    }
}
