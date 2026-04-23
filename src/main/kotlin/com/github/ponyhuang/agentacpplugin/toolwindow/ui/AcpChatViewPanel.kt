package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.StopReason
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
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
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.SwingUtilities

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
    private val smartScroller = SmartScroller(messageScrollPane.verticalScrollBar)
    private val permissionCardsByRequestId = linkedMapOf<String, PermissionRequestCardPanel>()

    init {
        border = JBUI.Borders.empty()
        background = UIUtil.getPanelBackground()
        add(messageScrollPane, BorderLayout.CENTER)
        bind()
        Disposer.register(parentDisposable, this)
    }

    @OptIn(UnstableApi::class)
    private fun bind() {
        uiScope.launch {
            combine(
                sessionService.messages,
                sessionService.isLoading,
                sessionService.lastStopReason
            ) { messages, isLoading, lastStopReason ->
                ConversationViewState(
                    messages = messages,
                    isLoading = isLoading,
                    lastStopReason = lastStopReason
                )
            }.collectLatest { state ->
                render(state)
            }
        }

        uiScope.launch {
            sessionService.pendingPermissionRequests
                .collectLatest { requests ->
                    refreshPermissionCards(requests)
                }
        }
    }

    private fun render(state: ConversationViewState) {
        val requestedVersion = renderVersion.incrementAndGet()
        ApplicationManager.getApplication().invokeLater {
            if (requestedVersion != renderVersion.get()) {
                return@invokeLater
            }

            val scrollSnapshot = captureScrollSnapshot()
            val visibleMessages = state.messages.filter { it.hasRenderableContent() }

            expandedThoughts.retainAll(state.messages.map { it.id }.toSet())
            permissionCardsByRequestId.clear()
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
                            onPermissionSubmit = { requestId, optionId ->
                                uiScope.launch {
                                    sessionService.submitPermissionRequest(requestId, optionId)
                                }
                            },
                            onPermissionCardCreated = { requestId, card ->
                                permissionCardsByRequestId[requestId] = card
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
            restoreScrollSnapshot(scrollSnapshot, requestedVersion)
            refreshPermissionCards(sessionService.pendingPermissionRequests.value)
        }
    }

    private fun refreshPermissionCards(requests: List<AcpSessionService.PermissionRequestInfo>) {
        ApplicationManager.getApplication().invokeLater {
            requests.forEach { request ->
                permissionCardsByRequestId[request.requestId]?.updateRequest(request)
            }
        }
    }

    private fun captureScrollSnapshot(): ScrollSnapshot {
        val model = messageScrollPane.verticalScrollBar.model
        return ScrollSnapshot(
            value = model.value,
            extent = model.extent,
            maximum = model.maximum
        )
    }

    private fun restoreScrollSnapshot(snapshot: ScrollSnapshot, requestedVersion: Int) {
        SwingUtilities.invokeLater {
            if (requestedVersion != renderVersion.get()) {
                return@invokeLater
            }

            val scrollBar = messageScrollPane.verticalScrollBar
            val model = scrollBar.model
            val targetValue = snapshot.restoreTarget(model)
            if (targetValue != model.value) {
                scrollBar.value = targetValue
            }
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
        smartScroller.dispose()
        uiScope.cancel()
    }
}

private data class ConversationViewState(
    val messages: List<AcpSessionService.ChatMessage>,
    val isLoading: Boolean,
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
    private val onPermissionSubmit: (String, String) -> Unit,
    private val onPermissionCardCreated: (String, PermissionRequestCardPanel) -> Unit,
    private val onCancelPrompt: () -> Unit,
    promptState: MessagePromptState?,
    thoughtExpanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        border = JBUI.Borders.empty()

        val chrome = MessageTemplatePanel(
            backgroundColor = backgroundForRole(message.role),
            borderColor = borderForRole(message.role),
            arc = 16,
            padding = JBUI.insets(10)
        )
        add(chrome, BorderLayout.CENTER)

        chrome.contentPanel.apply {
            layout = BorderLayout(0, JBUI.scale(8))
            add(createHeader(), BorderLayout.NORTH)
            add(createBody(thoughtExpanded, onThoughtToggled), BorderLayout.CENTER)
            promptState?.let { add(createFooter(it), BorderLayout.SOUTH) }
        }
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
                        val request = entry.request
                        val card = PermissionRequestCardPanel(
                            request = request,
                            onSubmit = { optionId ->
                                onPermissionSubmit(request.requestId, optionId)
                            }
                        )
                        onPermissionCardCreated(request.requestId, card)
                        add(
                            card
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

private class ThoughtPanel(
    thought: String,
    expanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private val contentPanel = JPanel(BorderLayout())

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)

        chrome.contentPanel.layout = BorderLayout(0, JBUI.scale(6))

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
        chrome.contentPanel.add(toggle, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        contentPanel.isVisible = expanded
        contentPanel.add(MarkdownPane(thought), BorderLayout.CENTER)
        chrome.contentPanel.add(contentPanel, BorderLayout.CENTER)
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
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)
        chrome.contentPanel.layout = BoxLayout(chrome.contentPanel, BoxLayout.Y_AXIS)

        chrome.contentPanel.add(
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
            chrome.contentPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
            details.forEachIndexed { index, line ->
                chrome.contentPanel.add(
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

internal class PermissionRequestCardPanel(
    request: AcpSessionService.PermissionRequestInfo,
    onSubmit: (String) -> Unit
) : JPanel() {
    private var currentRequest = request
    private val titleLabel = JBLabel()
    private val buttonGroup = ButtonGroup()
    private val radios = mutableListOf<Pair<AcpSessionService.PermissionOptionInfo, JRadioButton>>()
    private val submitButton = JButton().apply {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        addActionListener {
            val selectedOption = radios.firstOrNull { (_, radio) -> radio.isSelected }?.first ?: return@addActionListener
            onSubmit(selectedOption.optionId)
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)
        chrome.contentPanel.layout = BoxLayout(chrome.contentPanel, BoxLayout.Y_AXIS)

        chrome.contentPanel.add(
            JBLabel("Allow?").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = LEFT_ALIGNMENT
            }
        )
        chrome.contentPanel.add(titleLabel.apply {
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = LEFT_ALIGNMENT
        })
        rebuildOptions()
    }

    fun updateRequest(request: AcpSessionService.PermissionRequestInfo) {
        val structureChanged =
            currentRequest.options != request.options || currentRequest.title != request.title
        currentRequest = request
        if (structureChanged) {
            rebuildOptions()
        } else {
            applyRequestState()
        }
        revalidate()
        repaint()
    }

    private fun rebuildOptions() {
        titleLabel.text = currentRequest.title

        while (componentCount > 1) {
            remove(1)
        }
        val contentPanel = templateContentPanel()
        while (contentPanel.componentCount > 2) {
            contentPanel.remove(2)
        }
        while (buttonGroup.elements.hasMoreElements()) {
            buttonGroup.remove(buttonGroup.elements.nextElement())
        }
        radios.clear()

        if (currentRequest.options.isEmpty()) {
            contentPanel.add(
                JBLabel("No permission options were provided by the agent.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        } else {
            currentRequest.options.forEachIndexed { index, option ->
                val radio = JRadioButton(buildPermissionOptionLabel(option)).apply {
                    isOpaque = false
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.emptyBottom(if (index == currentRequest.options.lastIndex) 0 else 6)
                    alignmentX = LEFT_ALIGNMENT
                }
                buttonGroup.add(radio)
                radios += option to radio
                contentPanel.add(radio)
            }

            contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            contentPanel.add(submitButton)
        }

        applyRequestState()
    }

    private fun applyRequestState() {
        titleLabel.text = currentRequest.title
        radios.forEachIndexed { index, (option, radio) ->
            radio.isSelected =
                currentRequest.selectedOptionId == option.optionId ||
                    (currentRequest.selectedOptionId == null && index == 0)
            radio.isEnabled = !currentRequest.submitted
        }
        submitButton.text = if (currentRequest.submitted) "Submitted" else "Submit"
        submitButton.isEnabled = !currentRequest.submitted
    }
}

private class MessageTemplatePanel(
    private val backgroundColor: JBColor,
    private val borderColor: JBColor,
    private val arc: Int,
    padding: java.awt.Insets
) : JPanel(BorderLayout()) {
    val contentPanel = TemplateContentPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
    }

    init {
        isOpaque = false
        add(contentPanel, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = backgroundColor
            g2.fill(
                RoundRectangle2D.Float(
                    0f,
                    0f,
                    width.toFloat() - 1f,
                    height.toFloat() - 1f,
                    JBUI.scale(arc).toFloat(),
                    JBUI.scale(arc).toFloat()
                )
            )
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = borderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(arc), JBUI.scale(arc))
        } finally {
            g2.dispose()
        }
    }
}

private class TemplateContentPanel : JPanel()

private fun nestedTemplatePanel(): MessageTemplatePanel {
    val base = UIUtil.getPanelBackground()
    return MessageTemplatePanel(
        backgroundColor = JBColor(
            ColorUtil.mix(base, UIUtil.getLabelForeground(), 0.03),
            ColorUtil.mix(base, UIUtil.getLabelForeground(), 0.07)
        ),
        borderColor = JBColor.namedColor("Component.borderColor", JBColor(0xC9C9C9, 0x5E6068)),
        arc = 14,
        padding = JBUI.insets(8)
    )
}

private fun PermissionRequestCardPanel.templateContentPanel(): JPanel {
    return (getComponent(0) as MessageTemplatePanel).contentPanel
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
