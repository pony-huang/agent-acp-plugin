package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.StopReason
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.ButtonGroup
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JRadioButton

class AcpConversationPanel(
    project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionService = project.getService(AcpSessionService::class.java)
    private val expandedThoughts = linkedSetOf<String>()

    private val statusPanel = SessionStatusPanel(
        onCancel = {
            uiScope.launch {
                sessionService.cancel()
            }
        }
    )
    private val messagePanel = JPanel().apply {
        layout = VerticalLayout(8)
        border = JBUI.Borders.empty(8)
        background = UIUtil.getPanelBackground()
    }
    private val messageScrollPane = JBScrollPane(messagePanel).apply {
        border = JBUI.Borders.empty()
        viewport.background = UIUtil.getPanelBackground()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        border = JBUI.Borders.empty()
        background = UIUtil.getPanelBackground()
        add(statusPanel, BorderLayout.NORTH)
        add(messageScrollPane, BorderLayout.CENTER)
        bind()
        Disposer.register(parentDisposable, this)
    }

    @OptIn(UnstableApi::class)
    private fun bind() {
        listOf(
            sessionService.messages,
            sessionService.isLoading,
            sessionService.sessionTitle,
            sessionService.sessionUpdatedAt,
            sessionService.latestPlanEntries,
            sessionService.latestUsage,
            sessionService.lastStopReason,
            sessionService.currentAgent,
            sessionService.availableModes,
            sessionService.currentModeId,
            sessionService.availableModels,
            sessionService.currentModelId,
            sessionService.availableCommands,
            sessionService.pendingPermissionRequests
        ).forEach { flow ->
            uiScope.launch {
                flow.collectLatest {
                    renderFromState()
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun renderFromState() {
        val state = ConversationViewState(
            messages = sessionService.messages.value,
            isLoading = sessionService.isLoading.value,
            sessionTitle = sessionService.sessionTitle.value,
            sessionUpdatedAt = sessionService.sessionUpdatedAt.value,
            latestPlanEntries = sessionService.latestPlanEntries.value,
            latestUsage = sessionService.latestUsage.value,
            lastStopReason = sessionService.lastStopReason.value,
            agentName = sessionService.currentAgent.value?.implementation?.title
                ?: sessionService.currentAgent.value?.implementation?.name,
            currentModeName = sessionService.availableModes.value.find {
                it.id.value == sessionService.currentModeId.value
            }?.name,
            currentModelName = sessionService.availableModels.value.find {
                it.modelId.value == sessionService.currentModelId.value
            }?.name,
            commandCount = sessionService.availableCommands.value.size,
            pendingPermissionRequests = sessionService.pendingPermissionRequests.value
        )
        render(state)
    }

    private fun render(state: ConversationViewState) {
        ApplicationManager.getApplication().invokeLater {
            val shouldStickToBottom = isNearBottom()

            statusPanel.update(state)

            expandedThoughts.retainAll(state.messages.map { it.id }.toSet())
            messagePanel.removeAll()
            if (state.messages.isEmpty() && !state.isLoading) {
                messagePanel.add(createEmptyState())
            } else {
                state.messages.forEach { message ->
                    messagePanel.add(
                        MessageCardPanel(
                            message = message,
                            thoughtExpanded = expandedThoughts.contains(message.id),
                            onThoughtToggled = { expanded ->
                                if (expanded) {
                                    expandedThoughts.add(message.id)
                                } else {
                                    expandedThoughts.remove(message.id)
                                }
                            }
                        )
                    )
                }
                state.pendingPermissionRequests.forEach { request ->
                    messagePanel.add(
                        PermissionRequestCardPanel(
                            request = request,
                            onSubmit = { optionId ->
                                uiScope.launch {
                                    sessionService.submitPermissionRequest(request.requestId, optionId)
                                }
                            }
                        )
                    )
                }
                if (state.isLoading) {
                    // 无需要进行显示
//                    messagePanel.add(createLoadingState())
                }
            }

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
                JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1),
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
        val scrollBar = messageScrollPane.verticalScrollBar
        return scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - JBUI.scale(24)
    }

    private fun scrollToBottom() {
        val scrollBar = messageScrollPane.verticalScrollBar
        scrollBar.value = scrollBar.maximum
    }

    override fun dispose() {
        uiScope.cancel()
    }
}

private data class ConversationViewState(
    val messages: List<AcpSessionService.ChatMessage>,
    val isLoading: Boolean,
    val sessionTitle: String?,
    val sessionUpdatedAt: Long?,
    val latestPlanEntries: List<AcpSessionService.SessionPlanItem>,
    val latestUsage: AcpSessionService.SessionUsageSummary?,
    val lastStopReason: StopReason?,
    val agentName: String?,
    val currentModeName: String?,
    val currentModelName: String?,
    val commandCount: Int,
    val pendingPermissionRequests: List<AcpSessionService.PermissionRequestInfo>
)

private class SessionStatusPanel(
    private val onCancel: () -> Unit
) : JPanel() {

    private val titleLabel = JBLabel("Conversation")
    private val metaLabel = JBLabel()
    private val usageLabel = BadgeLabel()
    private val stopReasonLabel = BadgeLabel()
    private val commandsLabel = BadgeLabel()
    private val modeLabel = BadgeLabel()
    private val modelLabel = BadgeLabel()
    private val cancelLink = ActionLink("Cancel") { onCancel() }
    private val planPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8)
        )

        val header = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(titleLabel.apply {
                        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D + 1f)
                    })
                    add(Box.createVerticalStrut(JBUI.scale(2)))
                    add(metaLabel.apply {
                        foreground = UIUtil.getContextHelpForeground()
                    })
                },
                BorderLayout.WEST
            )
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(modeLabel)
                    add(modelLabel)
                    add(commandsLabel)
                    add(usageLabel)
                    add(stopReasonLabel)
                    add(cancelLink)
                },
                BorderLayout.EAST
            )
        }

        add(header)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(planPanel)
    }

    fun update(state: ConversationViewState) {
        titleLabel.text = state.sessionTitle ?: "Conversation"
        metaLabel.text = buildMetaText(state)
        usageLabel.update("Usage", state.latestUsage?.toDisplayText())
        stopReasonLabel.update("Stop", state.lastStopReason?.toDisplayText())
        commandsLabel.update("Commands", state.commandCount.takeIf { it > 0 }?.toString())
        modeLabel.update("Mode", state.currentModeName)
        modelLabel.update("Model", state.currentModelName)
        cancelLink.isVisible = state.isLoading

        planPanel.removeAll()
        if (state.latestPlanEntries.isNotEmpty()) {
            planPanel.add(
                JBLabel("Plan").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyBottom(4)
                }
            )
            state.latestPlanEntries.forEachIndexed { index, item ->
                planPanel.add(PlanEntryRow(item))
                if (index != state.latestPlanEntries.lastIndex) {
                    planPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
                }
            }
        }
        planPanel.isVisible = state.latestPlanEntries.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun buildMetaText(state: ConversationViewState): String {
        val parts = buildList {
            state.agentName?.takeIf { it.isNotBlank() }?.let { add(it) }
            state.sessionUpdatedAt?.let { add("Updated ${formatTimestamp(it)}") }
            state.isLoading.takeIf { it }?.let { add("Running") }
        }
        return parts.joinToString("  •  ")
    }
}

private class PlanEntryRow(item: AcpSessionService.SessionPlanItem) : JPanel() {
    init {
        layout = BorderLayout(JBUI.scale(8), 0)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(priorityColor(item.priority), 1, 3, 1, 1),
            JBUI.Borders.empty(6, 8)
        )
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

        add(
            JBLabel(item.content).apply {
                foreground = UIUtil.getLabelForeground()
            },
            BorderLayout.CENTER
        )
        add(
            JBLabel(item.status.toDisplayLabel()).apply {
                foreground = statusColor(item.status)
            },
            BorderLayout.EAST
        )
    }
}

private class BadgeLabel : JBLabel() {
    init {
        isOpaque = true
        background = JBColor.namedColor("ToolTip.background", JBColor(0xF4F4F4, 0x3C3F41))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(3, 6)
        )
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    }

    fun update(prefix: String, value: String?) {
        if (value.isNullOrBlank()) {
            isVisible = false
            text = ""
            return
        }
        text = "$prefix: $value"
        isVisible = true
    }
}

private class MessageCardPanel(
    val message: AcpSessionService.ChatMessage,
    thoughtExpanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = backgroundForRole(message.role)
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(10)
        )

        add(
            JBLabel(if (message.role == "user") "You" else "Assistant").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = LEFT_ALIGNMENT
            }
        )

        if (!message.thought.isNullOrBlank()) {
            add(ThoughtPanel(message.thought, thoughtExpanded, onThoughtToggled))
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

private class ThoughtPanel(
    thought: String,
    expanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private val contentPanel = JPanel(BorderLayout())

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout(0, JBUI.scale(6))
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6, 8)
        )

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
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(statusColor(toolCall.status), 1, 3, 1, 1),
            JBUI.Borders.empty(6, 8)
        )

        add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(
                    JBLabel("${kindLabel(toolCall.kind)} ${toolCall.title}").apply {
                        foreground = UIUtil.getLabelForeground()
                    },
                    BorderLayout.WEST
                )
                add(
                    JBLabel(toolCall.status.toDisplayLabel()).apply {
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
}

private class PermissionRequestCardPanel(
    request: AcpSessionService.PermissionRequestInfo,
    onSubmit: (String) -> Unit
) : JPanel() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = JBColor(
            ColorUtil.mix(UIUtil.getPanelBackground(), JBColor(0xFFF1CF, 0x54452A), 0.75),
            ColorUtil.mix(UIUtil.getPanelBackground(), JBColor(0xFFF1CF, 0x54452A), 0.45)
        )
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor(0xD39E00, 0xF2C46F), 1, 3, 1, 1),
            JBUI.Borders.empty(10)
        )

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
                val selected = request.selectedOptionId == option.optionId || (request.selectedOptionId == null && index == 0)
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
            pre { background: #${ColorUtil.toHex(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.06))}; border: 1px solid #${ColorUtil.toHex(JBColor.border())}; padding: 8px; margin: 0 0 8px 0; }
            code { font-family: monospace; background: #${ColorUtil.toHex(ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.08))}; }
            ul, ol { margin-top: 0; margin-bottom: 8px; padding-left: 18px; }
            li { margin-bottom: 4px; }
            """.trimIndent()
        )
        text = renderHtml(content)
        caretPosition = 0
    }
}

private val markdownFlavour = GFMFlavourDescriptor()
private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

private fun renderHtml(text: String): String {
    val parsedTree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(text)
    return HtmlGenerator(text, parsedTree, markdownFlavour).generateHtml()
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

private fun statusColor(status: String): Color {
    return when (status) {
        "pending" -> JBColor(0xA15C00, 0xF2C46F)
        "in_progress" -> JBColor(0x0B65C2, 0x73B7FF)
        "completed" -> JBColor(0x2B7A0B, 0x6FCF5D)
        "failed" -> JBColor(0xC0392B, 0xFF8A80)
        else -> UIUtil.getContextHelpForeground()
    }
}

private fun String.toDisplayLabel(): String {
    return when (this) {
        "pending" -> "Queued"
        "in_progress" -> "Running"
        "completed" -> "Done"
        "failed" -> "Failed"
        else -> replace('_', ' ')
    }
}

private fun StopReason.toDisplayText(): String {
    return when (this) {
        StopReason.END_TURN -> "End turn"
        StopReason.MAX_TOKENS -> "Max tokens"
        StopReason.MAX_TURN_REQUESTS -> "Max turns"
        StopReason.REFUSAL -> "Refusal"
        StopReason.CANCELLED -> "Cancelled"
    }
}

private fun AcpSessionService.SessionUsageSummary.toDisplayText(): String {
    val tokenText = "$usedTokens/$totalTokens"
    val costText = if (costAmount != null && !costCurrency.isNullOrBlank()) {
        " • %.4f %s".format(costAmount, costCurrency)
    } else {
        ""
    }
    return tokenText + costText
}

private fun formatTimestamp(timestamp: Long): String {
    return timeFormatter.format(Instant.ofEpochMilli(timestamp))
}

private fun buildPermissionOptionLabel(option: AcpSessionService.PermissionOptionInfo): String {
    val parts = buildList {
        add(option.label)
        option.kind?.takeIf { it.isNotBlank() }?.let { add(it.replace('_', ' ')) }
    }
    return parts.joinToString(" • ")
}

private fun priorityColor(priority: String): Color {
    return when (priority) {
        "high" -> JBColor(0xC0392B, 0xFF8A80)
        "medium" -> JBColor(0xA15C00, 0xF2C46F)
        "low" -> JBColor(0x2B7A0B, 0x6FCF5D)
        else -> JBColor.border()
    }
}
