package github.ponyhuang.acpplugin.toolwindow.ui.chat

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.ui.MessageTemplatePanel
import github.ponyhuang.acpplugin.toolwindow.ui.MessageRenderModel
import github.ponyhuang.acpplugin.toolwindow.ui.legacyRenderableEntries
import github.ponyhuang.acpplugin.toolwindow.ui.PermissionRequestCardPanel
import github.ponyhuang.acpplugin.toolwindow.ui.nestedMessageBubblePanel
import github.ponyhuang.acpplugin.toolwindow.ui.toolcall.ToolCallRow
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel

internal class MessageCardPanel(
    private val project: com.intellij.openapi.project.Project,
    message: AcpSessionService.ChatMessage,
    private val onPermissionSubmit: (String, String) -> Unit,
    private val onPermissionBeforeSubmit: () -> Unit,
    private val onPermissionCardUpdated: () -> Unit,
    private val onPermissionCardCreated: (String, PermissionRequestCardPanel) -> Unit,
    private val onCancelPrompt: () -> Unit,
    promptState: MessagePromptState?,
    thoughtExpanded: Boolean,
    private val onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private var currentMessage: AcpSessionService.ChatMessage = message
    private var currentPromptState: MessagePromptState? = promptState
    private var currentThoughtExpanded: Boolean = thoughtExpanded
    private val bubblePanel = MessageTemplatePanel(
        backgroundColor = backgroundForRole(message.role),
        borderColor = borderForRole(message.role),
        arc = 16,
        padding = JBUI.insets(10)
    )
    private val headerLabel = JBLabel()
    private val bodyPanel = JPanel()
    private var footer: MessagePromptFooter? = null
    private val entrySlots = mutableListOf<EntrySlot>()

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    val message: AcpSessionService.ChatMessage
        get() = currentMessage

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        border = JBUI.Borders.empty()
        add(bubblePanel, BorderLayout.CENTER)

        bubblePanel.contentPanel.apply {
            layout = BorderLayout(0, JBUI.scale(8))
            add(headerLabel, BorderLayout.NORTH)
            add(bodyPanel, BorderLayout.CENTER)
        }
        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.alignmentX = LEFT_ALIGNMENT
        bodyPanel.isOpaque = false
        applyMessage(currentMessage, currentPromptState, currentThoughtExpanded, forceRebuildBody = true, onThoughtToggled = onThoughtToggled)
    }

    fun update(model: MessageRenderModel) {
        applyMessage(
            message = model.message,
            promptState = model.promptState,
            thoughtExpanded = model.thoughtExpanded,
            forceRebuildBody = false,
            onThoughtToggled = { expanded -> onThoughtToggled(expanded) }
        )
    }

    fun collectPermissionCards(target: MutableMap<String, PermissionRequestCardPanel>) {
        entrySlots.forEach { entrySlot ->
            val entryView = entrySlot.view
            if (entryView is PermissionRequestEntryView) {
                target[entryView.currentRequestId] = entryView.card
            }
        }
    }

    private fun applyMessage(
        message: AcpSessionService.ChatMessage,
        promptState: MessagePromptState?,
        thoughtExpanded: Boolean,
        forceRebuildBody: Boolean,
        onThoughtToggled: (Boolean) -> Unit
    ) {
        currentMessage = message
        currentPromptState = promptState
        currentThoughtExpanded = thoughtExpanded

        headerLabel.text = if (message.role == "user") {
            MyBundle.message("chat.role.user")
        } else {
            MyBundle.message("chat.role.assistant")
        }
        headerLabel.foreground = UIUtil.getContextHelpForeground()
        headerLabel.alignmentX = LEFT_ALIGNMENT

        val nextEntries = message.entries.ifEmpty { message.legacyRenderableEntries() }
        val shouldRebuildBody =
            forceRebuildBody ||
                entrySlots.size != nextEntries.size ||
                entrySlots.zip(nextEntries).any { (slot, entry) -> slot.structureKey != structureKeyFor(entry) }

        if (shouldRebuildBody) {
            rebuildBody(nextEntries, thoughtExpanded, onThoughtToggled)
        } else {
            entrySlots.zip(nextEntries).forEach { (slot, entry) ->
                slot.view.update(entry, thoughtExpanded)
            }
        }

        updateFooter(promptState)
        bodyPanel.revalidate()
        bubblePanel.contentPanel.revalidate()
        repaint()
    }

    private fun rebuildBody(
        entries: List<AcpSessionService.MessageEntry>,
        thoughtExpanded: Boolean,
        onThoughtToggled: (Boolean) -> Unit
    ) {
        entrySlots.forEach { it.view.dispose() }
        bodyPanel.removeAll()
        entrySlots.clear()
        entries.forEachIndexed { index, entry ->
            val entryView = createEntryView(entry, thoughtExpanded, onThoughtToggled)
            val slot = EntrySlot(
                structureKey = structureKeyFor(entry),
                view = entryView,
                baseBorder = entryView.component.border
            )
            entrySlots += slot
            updateEntrySpacing(slot, hasBottomGap = index != entries.lastIndex)
            bodyPanel.add(slot.view.component)
        }
    }

    private fun createEntryView(
        entry: AcpSessionService.MessageEntry,
        thoughtExpanded: Boolean,
        onThoughtToggled: (Boolean) -> Unit
    ): MessageEntryView {
        return when (entry) {
            is AcpSessionService.MessageEntry.Content -> MarkdownEntryView(entry.text)
            is AcpSessionService.MessageEntry.PermissionRequest -> {
                val request = entry.request
                PermissionRequestEntryView(
                    requestId = request.requestId,
                    card = PermissionRequestCardPanel(
                        request = request,
                        onSubmit = { optionId ->
                            onPermissionSubmit(request.requestId, optionId)
                        },
                        onBeforeSubmit = onPermissionBeforeSubmit,
                        onRequestUpdated = onPermissionCardUpdated
                    )
                ).also { onPermissionCardCreated(request.requestId, it.card) }
            }
            is AcpSessionService.MessageEntry.Thought ->
                ThoughtEntryView(
                    thoughtPanel = ThoughtPanel(entry.text, thoughtExpanded, onThoughtToggled),
                    onThoughtToggled = onThoughtToggled
                )
            is AcpSessionService.MessageEntry.ToolCall ->
                ToolCallEntryView(ToolCallRow(project, entry.toolCall))
        }
    }

    fun dispose() {
        entrySlots.forEach { it.view.dispose() }
        entrySlots.clear()
    }

    private fun updateFooter(promptState: MessagePromptState?) {
        val existingFooter = footer
        when {
            promptState == null && existingFooter != null -> {
                bubblePanel.contentPanel.remove(existingFooter)
                footer = null
            }
            promptState != null && existingFooter == null -> {
                footer = MessagePromptFooter(
                    state = promptState,
                    onCancel = onCancelPrompt
                ).apply {
                    isOpaque = false
                }
                bubblePanel.contentPanel.add(footer, BorderLayout.SOUTH)
            }
            promptState != null && existingFooter != null -> {
                existingFooter.updateState(promptState)
            }
        }
    }

    private fun structureKeyFor(entry: AcpSessionService.MessageEntry): String {
        return when (entry) {
            is AcpSessionService.MessageEntry.Content -> "content"
            is AcpSessionService.MessageEntry.Thought -> "thought"
            is AcpSessionService.MessageEntry.ToolCall -> "tool:${entry.toolCall.toolCallId}"
            is AcpSessionService.MessageEntry.PermissionRequest -> "permission:${entry.request.requestId}"
        }
    }

    private fun updateEntrySpacing(slot: EntrySlot, hasBottomGap: Boolean) {
        slot.view.component.border = if (hasBottomGap) {
            JBUI.Borders.compound(slot.baseBorder, JBUI.Borders.emptyBottom(8))
        } else {
            slot.baseBorder
        }
    }

    private data class EntrySlot(
        val structureKey: String,
        val view: MessageEntryView,
        val baseBorder: javax.swing.border.Border?
    )

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

    private fun borderForRole(role: String): JBColor {
        return if (role == "user") {
            JBColor(
                JBColor.namedColor("Component.focusColor", JBColor(0x8AB8E8, 0x3B6E99)),
                JBColor.namedColor("Component.focusColor", JBColor(0x8AB8E8, 0x3B6E99))
            )
        } else {
            JBColor(
                ColorUtil.mix(JBColor.border(), UIUtil.getLabelForeground(), 0.12),
                ColorUtil.mix(JBColor.border(), UIUtil.getLabelForeground(), 0.18)
            )
        }
    }
}

