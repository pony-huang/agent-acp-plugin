package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.StopReason
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*

class AcpChatViewPanel(
    project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionService = project.getService(AcpSessionService::class.java)
    private val expandedThoughts = linkedSetOf<String>()
    private val renderVersion = AtomicInteger()
    private val messagePanel = JPanel().apply {
        layout = GridBagLayout()
        border = JBUI.Borders.empty(8, 4)
        background = UIUtil.getPanelBackground()
    }
    private val messageScrollPane = JBScrollPane(messagePanel).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    // Smart scrolling state
    private var adjustScrollBar = true
    private var previousValue = -1
    private var previousMaximum = -1

    private val smartScrollerListener: SmartScrollerListener

    init {
        border = JBUI.Borders.empty()
        background = UIUtil.getPanelBackground()
        add(messageScrollPane, BorderLayout.CENTER)
        smartScrollerListener = SmartScrollerListener()
        messageScrollPane.verticalScrollBar.addAdjustmentListener(smartScrollerListener)
        bind()
        Disposer.register(parentDisposable, this)
    }

    private inner class SmartScrollerListener : java.awt.event.AdjustmentListener {
        override fun adjustmentValueChanged(e: java.awt.event.AdjustmentEvent) {
            val scrollBar = e.source as JScrollBar
            val model = scrollBar.model
            val value = model.value
            val extent = model.extent
            val maximum = model.maximum

            val valueChanged = previousValue != value
            val maximumChanged = previousMaximum != maximum

            if (valueChanged && !maximumChanged) {
                adjustScrollBar = value + extent >= maximum
            }

            if (adjustScrollBar) {
                scrollBar.removeAdjustmentListener(this)
                val newValue = maximum - extent
                scrollBar.value = newValue
                scrollBar.addAdjustmentListener(this)
            }

            previousValue = value
            previousMaximum = maximum
        }
    }

    @OptIn(UnstableApi::class)
    private fun bind() {
        uiScope.launch {
            combine(
                sessionService.messages,
                sessionService.isLoading,
                sessionService.pendingPermissionRequests,
                sessionService.lastStopReason
            ) { messages, isLoading, pendingPermissionRequests, lastStopReason ->
                ConversationViewState(
                    messages = messages,
                    isLoading = isLoading,
                    pendingPermissionRequests = pendingPermissionRequests,
                    lastStopReason = lastStopReason
                )
            }.distinctUntilChanged().collectLatest { state ->
                render(state)
            }
        }
    }

    private fun render(state: ConversationViewState) {
        val requestedVersion = renderVersion.incrementAndGet()
        ApplicationManager.getApplication().invokeLater {
            if (requestedVersion != renderVersion.get()) {
                return@invokeLater
            }

            val visibleMessages = state.messages.filter { it.hasRenderableContent() }

            expandedThoughts.retainAll(state.messages.map { it.id }.toSet())
            messagePanel.removeAll()
            if (visibleMessages.isEmpty() && !state.isLoading) {
                addMessageRow(createEmptyState(), 0, false)
                addMessageSpacer(1)
            } else {
                var rowIndex = 0
                val latestAssistantMessageId = visibleMessages.lastOrNull { it.role == "assistant" }?.id
                visibleMessages.forEach { message ->
                    addMessageRow(
                        MessageCardPanel(
                            message = message,
                            pendingPermissionRequests = state.pendingPermissionRequests,
                            onPermissionSubmit = { requestId, optionId ->
                                uiScope.launch {
                                    sessionService.submitPermissionRequest(requestId, optionId)
                                }
                            },
                            onCancelPrompt = {
                                uiScope.launch {
                                    sessionService.cancel()
                                }
                            },
                            promptState = messagePromptState(
                                message = message,
                                latestAssistantMessageId = latestAssistantMessageId,
                                isLoading = state.isLoading,
                                lastStopReason = state.lastStopReason
                            ),
                            thoughtExpanded = expandedThoughts.contains(message.id),
                            onThoughtToggled = { expanded ->
                                if (expanded) {
                                    expandedThoughts.add(message.id)
                                } else {
                                    expandedThoughts.remove(message.id)
                                }
                            }
                        ),
                        rowIndex++
                    )
                }
                addMessageSpacer(rowIndex)
            }

            messagePanel.revalidate()
            messagePanel.repaint()
        }
    }

    private fun addMessageRow(component: JComponent, row: Int, addBottomGap: Boolean = true) {
        messagePanel.add(
            component.apply {
                alignmentX = LEFT_ALIGNMENT
            },
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = JBUI.insetsBottom(if (addBottomGap) 8 else 0)
            }
        )
    }

    private fun addMessageSpacer(row: Int) {
        messagePanel.add(
            JPanel().apply {
                isOpaque = false
            },
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                weightx = 1.0
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            }
        )
    }

    private fun createEmptyState(): JComponent {
        val base = UIUtil.getPanelBackground()
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = JBColor(
                ColorUtil.mix(base, JBColor(0xF5F7FA, 0x2F3338), 0.92),
                ColorUtil.mix(base, JBColor(0xF5F7FA, 0x2F3338), 0.82)
            )
            border = JBUI.Borders.empty(16)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = LEFT_ALIGNMENT

            add(
                JBLabel("No conversation yet").apply {
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.emptyBottom(6)
                    alignmentX = LEFT_ALIGNMENT
                }
            )
            add(
                JBLabel("Start a conversation to see ACP messages here.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        }
    }

    override fun dispose() {
        messageScrollPane.verticalScrollBar.removeAdjustmentListener(smartScrollerListener)
        uiScope.cancel()
    }
}

private data class ConversationViewState(
    val messages: List<AcpSessionService.ChatMessage>,
    val isLoading: Boolean,
    val pendingPermissionRequests: List<AcpSessionService.PermissionRequestInfo>,
    val lastStopReason: StopReason?
)

private fun AcpSessionService.ChatMessage.hasRenderableContent(): Boolean {
    if (entries.isNotEmpty()) {
        return true
    }
    if (content.isNotBlank()) {
        return true
    }
    if (!thought.isNullOrBlank()) {
        return true
    }
    return toolCalls.isNotEmpty()
}

private class MessageCardPanel(
    val message: AcpSessionService.ChatMessage,
    pendingPermissionRequests: List<AcpSessionService.PermissionRequestInfo>,
    private val onPermissionSubmit: (String, String) -> Unit,
    private val onCancelPrompt: () -> Unit,
    promptState: MessagePromptState?,
    thoughtExpanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private val permissionRequestsById = pendingPermissionRequests.associateBy { it.requestId }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout(0, JBUI.scale(8))
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = backgroundForRole(message.role)
        border = JBUI.Borders.empty(10)

        add(createHeader(), BorderLayout.NORTH)
        add(createBody(thoughtExpanded, onThoughtToggled), BorderLayout.CENTER)
        promptState?.let { add(createFooter(it), BorderLayout.SOUTH) }
    }

    private fun createHeader(): JComponent {
        return JBLabel(if (message.role == "user") "You" else "Assistant").apply {
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun createBody(
        thoughtExpanded: Boolean,
        onThoughtToggled: (Boolean) -> Unit
    ): JComponent {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false

            val entries = message.entries.ifEmpty {
                buildList {
                    message.thought?.takeIf { it.isNotBlank() }?.let {
                        add(AcpSessionService.MessageEntry.Thought(it))
                    }
                    message.toolCalls.forEach { add(AcpSessionService.MessageEntry.ToolCall(it)) }
                    message.content.takeIf { it.isNotBlank() }?.let {
                        add(AcpSessionService.MessageEntry.Content(it))
                    }
                }
            }

            entries.forEachIndexed { index, entry ->
                when (entry) {
                    is AcpSessionService.MessageEntry.Content -> add(MarkdownPane(entry.text))
                    is AcpSessionService.MessageEntry.PermissionRequest -> {
                        val request = permissionRequestsById[entry.request.requestId] ?: entry.request
                        add(
                            PermissionRequestCardPanel(
                                request = request,
                                onSubmit = { optionId ->
                                    onPermissionSubmit(request.requestId, optionId)
                                }
                            )
                        )
                    }
                    is AcpSessionService.MessageEntry.Thought ->
                        add(ThoughtPanel(entry.text, thoughtExpanded, onThoughtToggled))
                    is AcpSessionService.MessageEntry.ToolCall ->
                        add(ToolCallRow(entry.toolCall))
                }

                if (index != entries.lastIndex) {
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                }
            }
        }
    }

    private fun createFooter(promptState: MessagePromptState): JComponent {
        return MessagePromptFooter(
            state = promptState,
            onCancel = onCancelPrompt
        ).apply {
            isOpaque = false
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

private class ThoughtPanel(
    thought: String,
    expanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private val contentPanel = JPanel(BorderLayout())

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout(0, JBUI.scale(6))
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(6, 8)

        val toggle = ActionLink(if (expanded) "Hide Thinking" else "Show Thinking").apply {
            addActionListener {
                val nextExpanded = !contentPanel.isVisible
                contentPanel.isVisible = nextExpanded
                text = if (nextExpanded) "Hide Thinking" else "Show Thinking"
                onThoughtToggled(nextExpanded)
                revalidate()
                repaint()
            }
        }
        add(toggle, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        contentPanel.isVisible = expanded
        contentPanel.add(MarkdownPane(thought), BorderLayout.CENTER)
        add(contentPanel, BorderLayout.CENTER)
    }
}

private class ToolCallListPanel(toolCalls: List<AcpSessionService.ToolCallInfo>) : JPanel() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

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
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(6, 8)

        add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(
                    JBLabel("${toolKindDisplay(toolCall.kind)} ${toolCall.title}").apply {
                        foreground = UIUtil.getLabelForeground()
                    },
                    BorderLayout.WEST
                )
                add(
                    ToolStatusLabel(toolCall.status),
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
}

private class MessagePromptFooter(
    state: MessagePromptState,
    onCancel: () -> Unit
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    init {
        isOpaque = false

        add(MessagePromptStatusIcon(state), BorderLayout.EAST)

        if (state == MessagePromptState.RUNNING) {
            add(
                ActionLink("Cancel").apply {
                    addActionListener { onCancel() }
                    toolTipText = "Cancel the current ACP response"
                },
                BorderLayout.WEST
            )
        }
    }
}

private enum class MessagePromptState {
    RUNNING,
    COMPLETED,
    WARNING
}

private class MessagePromptStatusIcon(state: MessagePromptState) : JBLabel() {
    private val animatedIcons = arrayOf(
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4,
        AllIcons.Process.Step_5,
        AllIcons.Process.Step_6,
        AllIcons.Process.Step_7,
        AllIcons.Process.Step_8
    )
    private val animationTimer = Timer(60, null)
    private val shouldAnimate = state == MessagePromptState.RUNNING
    private var animationFrame = 0

    init {
        isOpaque = false
        icon = when (state) {
            MessagePromptState.RUNNING -> animatedIcons.first()
            MessagePromptState.COMPLETED -> AllIcons.General.InspectionsOK
            MessagePromptState.WARNING -> AllIcons.General.Warning
        }
        if (shouldAnimate) {
            animationTimer.addActionListener {
                animationFrame = (animationFrame + 1) % animatedIcons.size
                icon = animatedIcons[animationFrame]
                repaint()
            }
            animationTimer.isRepeats = true
        }
    }

    override fun addNotify() {
        super.addNotify()
        if (shouldAnimate && !animationTimer.isRunning) {
            animationFrame = 0
            icon = animatedIcons[animationFrame]
            animationTimer.start()
        }
    }

    override fun removeNotify() {
        animationTimer.stop()
        super.removeNotify()
    }
}

private class PermissionRequestCardPanel(
    request: AcpSessionService.PermissionRequestInfo,
    onSubmit: (String) -> Unit
) : JPanel() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(10)

        add(
            JBLabel("Permission Request").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = LEFT_ALIGNMENT
            }
        )
        add(
            JBLabel(request.title).apply {
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = LEFT_ALIGNMENT
            }
        )

        if (request.options.isEmpty()) {
            add(
                JBLabel("No permission options were provided by the agent.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        } else {
            val buttonGroup = ButtonGroup()
            val radios = mutableListOf<JRadioButton>()
            request.options.forEachIndexed { index, option ->
                val selected =
                    request.selectedOptionId == option.optionId || (request.selectedOptionId == null && index == 0)
                val radio = JRadioButton(buildPermissionOptionLabel(option)).apply {
                    isOpaque = false
                    isSelected = selected
                    isEnabled = !request.submitted
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.emptyBottom(if (index == request.options.lastIndex) 0 else 6)
                    alignmentX = LEFT_ALIGNMENT
                }
                buttonGroup.add(radio)
                radios += radio
                add(radio)
            }

            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(
                JButton(if (request.submitted) "Submitted" else "Submit").apply {
                    isEnabled = !request.submitted
                    alignmentX = LEFT_ALIGNMENT
                    addActionListener {
                        val selectedIndex = radios.indexOfFirst { it.isSelected }
                        if (selectedIndex >= 0) {
                            onSubmit(request.options[selectedIndex].optionId)
                        }
                    }
                }
            )
        }
    }
}

private class MarkdownPane(content: String) : JEditorPane() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        alignmentX = LEFT_ALIGNMENT
        isEditable = false
        isOpaque = false
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty()
        editorKit = HTMLEditorKitBuilder.simple()
        val htmlEditorKit = editorKit as javax.swing.text.html.HTMLEditorKit
        htmlEditorKit.styleSheet.addRule(
            """
            body { font-family: sans-serif; font-size: 12px; color: #${ColorUtil.toHex(UIUtil.getLabelForeground())}; margin: 0; }
            p { margin: 0 0 8px 0; }
            pre { background: #${
                ColorUtil.toHex(
                    ColorUtil.mix(
                        UIUtil.getPanelBackground(),
                        UIUtil.getLabelForeground(),
                        0.06
                    )
                )
            }; border: 1px solid #${ColorUtil.toHex(JBColor.border())}; padding: 8px; margin: 0 0 8px 0; }
            code { font-family: monospace; background: #${
                ColorUtil.toHex(
                    ColorUtil.mix(
                        UIUtil.getPanelBackground(),
                        UIUtil.getLabelForeground(),
                        0.08
                    )
                )
            }; }
            ul, ol { margin-top: 0; margin-bottom: 8px; padding-left: 18px; }
            li { margin-bottom: 4px; }
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

private class ToolStatusLabel(status: String) : JPanel(BorderLayout(JBUI.scale(4), 0)) {
    private val statusIcon = ToolStatusIcon(status)
    private val statusText = JBLabel(status.toDisplayLabel()).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        isOpaque = false
        add(statusText, BorderLayout.WEST)
        add(statusIcon, BorderLayout.EAST)
    }
}

private class ToolStatusIcon(status: String) : JBLabel() {
    private val animatedIcons = arrayOf(
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4,
        AllIcons.Process.Step_5,
        AllIcons.Process.Step_6,
        AllIcons.Process.Step_7,
        AllIcons.Process.Step_8
    )
    private val animationTimer = Timer(60, null)
    private val shouldAnimate = status == "in_progress"
    private var animationFrame = 0

    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        icon = statusIconFor(status)
        if (shouldAnimate) {
            animationTimer.addActionListener {
                animationFrame = (animationFrame + 1) % animatedIcons.size
                icon = animatedIcons[animationFrame]
                repaint()
            }
            animationTimer.isRepeats = true
        }
    }

    override fun addNotify() {
        super.addNotify()
        if (shouldAnimate && !animationTimer.isRunning) {
            animationFrame = 0
            icon = animatedIcons[animationFrame]
            animationTimer.start()
        }
    }

    override fun removeNotify() {
        animationTimer.stop()
        super.removeNotify()
    }
}

private fun toolKindDisplay(kind: String?): String {
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

private fun statusIconFor(status: String): Icon {
    return when (status) {
        "pending" -> AllIcons.Process.Step_passive
        "in_progress" -> AllIcons.Process.Step_1
        "completed" -> AllIcons.General.InspectionsOK
        "cancelled" -> AllIcons.Actions.Cancel
        "failed" -> AllIcons.General.Error
        else -> AllIcons.General.Information
    }
}

private fun String.toDisplayLabel(): String {
    return when (this) {
        "pending" -> "Queued"
        "in_progress" -> "Running"
        "completed" -> "Done"
        "cancelled" -> "Cancelled"
        "failed" -> "Failed"
        else -> replace('_', ' ')
    }
}

private fun buildPermissionOptionLabel(option: AcpSessionService.PermissionOptionInfo): String {
    val parts = buildList {
        add(option.label)
        option.kind?.takeIf { it.isNotBlank() }?.let { add(it.replace('_', ' ')) }
    }
    return parts.joinToString(" • ")
}

private fun messagePromptState(
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
