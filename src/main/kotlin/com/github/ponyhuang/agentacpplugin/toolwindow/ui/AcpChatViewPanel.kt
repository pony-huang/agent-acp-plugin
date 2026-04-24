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
    private val messageRowControllers = linkedMapOf<String, MessageRowController>()
    private val emptyStateComponent by lazy { createEmptyState() }
    private val spacerComponent = JPanel().apply {
        isOpaque = false
    }
    private var hasEmptyState = false

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
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisplayable) {
                            refreshPermissionCards(requests)
                        }
                    }
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
            val showEmptyState = visibleMessages.isEmpty() && !state.isLoading

            expandedThoughts.retainAll(state.messages.map { it.id }.toSet())
            val renderModels = visibleMessages.toRenderModels(
                isLoading = state.isLoading,
                lastStopReason = state.lastStopReason,
                expandedThoughts = expandedThoughts
            )

            val reconciled = runCatching {
                reconcileRows(renderModels, showEmptyState)
            }.getOrElse {
                renderFully(renderModels, showEmptyState)
                true
            }

            if (reconciled) {
                syncPermissionCardRegistry()
                messagePanel.revalidate()
                messageScrollPane.viewport.revalidate()
                messageScrollPane.revalidate()
                messagePanel.repaint()
                restoreScrollSnapshot(scrollSnapshot, requestedVersion)
                refreshPermissionCards(sessionService.pendingPermissionRequests.value)
            }
        }
    }

    private fun reconcileRows(renderModels: List<MessageRenderModel>, showEmptyState: Boolean): Boolean {
        val previousIds = messageRowControllers.keys.toList()
        val nextIds = renderModels.map { it.message.id }
        var replacedExistingRow = false

        val staleIds = previousIds.filterNot(nextIds::contains)
        staleIds.forEach { staleId ->
            messageRowControllers.remove(staleId)?.dispose()
        }

        // Always update existing controllers to handle content-only changes
        // even when child order is preserved (canKeepChildOrder case)
        renderModels.forEach { model ->
            val controller = messageRowControllers[model.message.id]
            if (controller == null) {
                messageRowControllers[model.message.id] = createRowController(model)
            } else {
                replacedExistingRow = controller.update(model) || replacedExistingRow
            }
        }

        syncPermissionCardRegistry()

        val canKeepChildOrder =
            !showEmptyState &&
                !hasEmptyState &&
                !replacedExistingRow &&
                previousIds == nextIds
        val canAppendOnly =
            !showEmptyState &&
                !hasEmptyState &&
                !replacedExistingRow &&
                nextIds.size > previousIds.size &&
                nextIds.subList(0, previousIds.size) == previousIds

        when {
            showEmptyState -> {
                rebuildPanelChildren(emptyList(), showEmptyState = true)
            }
            canKeepChildOrder -> {
                // Keep mounted row components in place when only their content changed.
            }
            canAppendOnly -> {
                appendRows(nextIds.drop(previousIds.size), startRow = previousIds.size)
                ensureSpacerAtRow(nextIds.size)
            }
            else -> {
                rebuildPanelChildren(nextIds, showEmptyState = false)
            }
        }

        hasEmptyState = showEmptyState
        return true
    }

    private fun renderFully(renderModels: List<MessageRenderModel>, showEmptyState: Boolean) {
        messageRowControllers.values.forEach { it.dispose() }
        messageRowControllers.clear()
        renderModels.forEach { model ->
            messageRowControllers[model.message.id] = createRowController(model)
        }
        syncPermissionCardRegistry()
        rebuildPanelChildren(
            orderedIds = renderModels.map { it.message.id },
            showEmptyState = showEmptyState
        )
        hasEmptyState = showEmptyState
    }

    private fun createRowController(model: MessageRenderModel): MessageRowController {
        return MessageRowController(
            initialModel = model,
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
            onThoughtToggled = { messageId, expanded ->
                if (expanded) {
                    expandedThoughts.add(messageId)
                } else {
                    expandedThoughts.remove(messageId)
                }
            }
        )
    }

    private fun rebuildPanelChildren(orderedIds: List<String>, showEmptyState: Boolean) {
        messagePanel.removeAll()
        if (showEmptyState) {
            addMessageRow(emptyStateComponent, 0, false)
            addMessageSpacer(1)
            return
        }
        if (orderedIds.isEmpty()) {
            addMessageSpacer(0)
            return
        }

        orderedIds.forEachIndexed { rowIndex, messageId ->
            val component = messageRowControllers[messageId]?.component ?: return@forEachIndexed
            addMessageRow(component, rowIndex)
        }
        addMessageSpacer(orderedIds.size)
    }

    private fun appendRows(appendedIds: List<String>, startRow: Int) {
        removeSpacerIfPresent()
        appendedIds.forEachIndexed { index, messageId ->
            val component = messageRowControllers[messageId]?.component ?: return@forEachIndexed
            addMessageRow(component, startRow + index)
        }
    }

    private fun ensureSpacerAtRow(row: Int) {
        removeSpacerIfPresent()
        addMessageSpacer(row)
    }

    private fun removeSpacerIfPresent() {
        if (spacerComponent.parent == messagePanel) {
            messagePanel.remove(spacerComponent)
        }
    }

    private fun syncPermissionCardRegistry() {
        permissionCardsByRequestId.clear()
        messageRowControllers.values.forEach { controller ->
            controller.collectPermissionCards(permissionCardsByRequestId)
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

            SwingUtilities.invokeLater {
                if (requestedVersion != renderVersion.get()) {
                    return@invokeLater
                }

                messagePanel.revalidate()
                messageScrollPane.viewport.revalidate()
                messageScrollPane.revalidate()
                messagePanel.repaint()

                val settledModel = scrollBar.model
                val settledTarget = snapshot.restoreTarget(settledModel)
                if (settledTarget != settledModel.value) {
                    scrollBar.value = settledTarget
                }
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
            spacerComponent,
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

private data class MessageRenderModel(
    val message: AcpSessionService.ChatMessage,
    val promptState: MessagePromptState?,
    val thoughtExpanded: Boolean
) {
    val structureKey: List<String> =
        message.entries.ifEmpty { message.legacyRenderableEntries() }.map { entry ->
            when (entry) {
                is AcpSessionService.MessageEntry.Content -> "content"
                is AcpSessionService.MessageEntry.Thought -> "thought"
                is AcpSessionService.MessageEntry.ToolCall -> "tool:${entry.toolCall.toolCallId}"
                is AcpSessionService.MessageEntry.PermissionRequest -> "permission:${entry.request.requestId}"
            }
        }
}

private class MessageRowController(
    initialModel: MessageRenderModel,
    private val onPermissionSubmit: (String, String) -> Unit,
    private val onCancelPrompt: () -> Unit,
    private val onThoughtToggled: (String, Boolean) -> Unit
) {
    private var model: MessageRenderModel = initialModel
    var component: MessageCardPanel = createComponent(initialModel)
        private set

    fun update(nextModel: MessageRenderModel): Boolean {
        var replaced = false
        if (model.structureKey != nextModel.structureKey || model.message.role != nextModel.message.role) {
            component = createComponent(nextModel)
            replaced = true
        } else {
            component.update(nextModel)
        }
        model = nextModel
        return replaced
    }

    fun collectPermissionCards(target: MutableMap<String, PermissionRequestCardPanel>) {
        component.collectPermissionCards(target)
    }

    fun dispose() {
        // No-op for now; row children are Swing-managed.
    }

    private fun createComponent(model: MessageRenderModel): MessageCardPanel {
        return MessageCardPanel(
            message = model.message,
            onPermissionSubmit = onPermissionSubmit,
            onPermissionCardCreated = { _, _ -> },
            onCancelPrompt = onCancelPrompt,
            promptState = model.promptState,
            thoughtExpanded = model.thoughtExpanded,
            onThoughtToggled = { expanded ->
                onThoughtToggled(model.message.id, expanded)
            }
        )
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

private fun AcpSessionService.ChatMessage.legacyRenderableEntries(): List<AcpSessionService.MessageEntry> {
    return buildList {
        thought?.takeIf { it.isNotBlank() }?.let {
            add(AcpSessionService.MessageEntry.Thought(it))
        }
        toolCalls.forEach { add(AcpSessionService.MessageEntry.ToolCall(it)) }
        content.takeIf { it.isNotBlank() }?.let {
            add(AcpSessionService.MessageEntry.Content(it))
        }
    }
}

private fun List<AcpSessionService.ChatMessage>.toRenderModels(
    isLoading: Boolean,
    lastStopReason: StopReason?,
    expandedThoughts: Set<String>
): List<MessageRenderModel> {
    val latestAssistantMessageId = lastOrNull { it.role == "assistant" }?.id
    return map { message ->
        MessageRenderModel(
            message = message,
            promptState = messagePromptState(
                message = message,
                latestAssistantMessageId = latestAssistantMessageId,
                isLoading = isLoading,
                lastStopReason = lastStopReason
            ),
            thoughtExpanded = expandedThoughts.contains(message.id)
        )
    }
}

private class MessageCardPanel(
    message: AcpSessionService.ChatMessage,
    private val onPermissionSubmit: (String, String) -> Unit,
    private val onPermissionCardCreated: (String, PermissionRequestCardPanel) -> Unit,
    private val onCancelPrompt: () -> Unit,
    promptState: MessagePromptState?,
    thoughtExpanded: Boolean,
    private val onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private var currentMessage: AcpSessionService.ChatMessage = message
    private var currentPromptState: MessagePromptState? = promptState
    private var currentThoughtExpanded: Boolean = thoughtExpanded
    private val chrome = MessageTemplatePanel(
        backgroundColor = backgroundForRole(message.role),
        borderColor = borderForRole(message.role),
        arc = 16,
        padding = JBUI.insets(10)
    )
    private val headerLabel = JBLabel()
    private val bodyPanel = JPanel()
    private var footer: MessagePromptFooter? = null
    private val entryViews = mutableListOf<MessageEntryView>()

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    val message: AcpSessionService.ChatMessage
        get() = currentMessage

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        border = JBUI.Borders.empty()
        add(chrome, BorderLayout.CENTER)

        chrome.contentPanel.apply {
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
        entryViews.forEach { entryView ->
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

        headerLabel.text = if (message.role == "user") "You" else "Assistant"
        headerLabel.foreground = UIUtil.getContextHelpForeground()
        headerLabel.alignmentX = LEFT_ALIGNMENT

        val nextEntries = message.entries.ifEmpty { message.legacyRenderableEntries() }
        val shouldRebuildBody =
            forceRebuildBody ||
                entryViews.size != nextEntries.size ||
                entryViews.zip(nextEntries).any { (view, entry) -> !view.canUpdate(entry) }

        if (shouldRebuildBody) {
            rebuildBody(nextEntries, thoughtExpanded, onThoughtToggled)
        } else {
            entryViews.zip(nextEntries).forEach { (view, entry) ->
                view.update(entry, thoughtExpanded)
            }
        }

        updateFooter(promptState)
        bodyPanel.revalidate()
        chrome.contentPanel.revalidate()
        revalidate()
        repaint()
    }

    private fun rebuildBody(
        entries: List<AcpSessionService.MessageEntry>,
        thoughtExpanded: Boolean,
        onThoughtToggled: (Boolean) -> Unit
    ) {
        bodyPanel.removeAll()
        entryViews.clear()
        entries.forEachIndexed { index, entry ->
            val entryView = createEntryView(entry, thoughtExpanded, onThoughtToggled)
            entryViews += entryView
            bodyPanel.add(entryView.component)
            if (index != entries.lastIndex) {
                bodyPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
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
                    card = PermissionRequestCardPanel(request = request) { optionId ->
                        onPermissionSubmit(request.requestId, optionId)
                    }
                ).also { onPermissionCardCreated(request.requestId, it.card) }
            }
            is AcpSessionService.MessageEntry.Thought ->
                ThoughtEntryView(
                    thoughtPanel = ThoughtPanel(entry.text, thoughtExpanded, onThoughtToggled),
                    onThoughtToggled = onThoughtToggled
                )
            is AcpSessionService.MessageEntry.ToolCall ->
                ToolCallEntryView(ToolCallRow(entry.toolCall))
        }
    }

    private fun updateFooter(promptState: MessagePromptState?) {
        val existingFooter = footer
        when {
            promptState == null && existingFooter != null -> {
                chrome.contentPanel.remove(existingFooter)
                footer = null
            }
            promptState != null && existingFooter == null -> {
                footer = MessagePromptFooter(
                    state = promptState,
                    onCancel = onCancelPrompt
                ).apply {
                    isOpaque = false
                }
                chrome.contentPanel.add(footer, BorderLayout.SOUTH)
            }
            promptState != null && existingFooter != null -> {
                existingFooter.updateState(promptState)
            }
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
    private val toggle = ActionLink("")
    private val markdownPane = MarkdownPane(thought)
    private var toggleHandler: ((Boolean) -> Unit)? = onThoughtToggled

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)

        chrome.contentPanel.layout = BorderLayout(0, JBUI.scale(6))

        toggle.addActionListener {
            val nextExpanded = !contentPanel.isVisible
            setExpanded(nextExpanded)
            toggleHandler?.invoke(nextExpanded)
        }
        chrome.contentPanel.add(toggle, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        contentPanel.add(markdownPane, BorderLayout.CENTER)
        chrome.contentPanel.add(contentPanel, BorderLayout.CENTER)
        update(thought, expanded, onThoughtToggled)
    }

    fun update(thought: String, expanded: Boolean, onThoughtToggled: (Boolean) -> Unit) {
        markdownPane.updateContent(thought)
        toggleHandler = onThoughtToggled
        setExpanded(expanded)
    }

    private fun setExpanded(expanded: Boolean) {
        contentPanel.isVisible = expanded
        toggle.text = if (expanded) "Hide Thinking" else "Show Thinking"
        revalidate()
        repaint()
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
    private val titleLabel = JBLabel()
    private val statusLabel = ToolStatusLabel(toolCall.status)
    private val detailsPanel = JPanel()

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
                    titleLabel.apply {
                        foreground = UIUtil.getLabelForeground()
                    },
                    BorderLayout.WEST
                )
                add(
                    statusLabel,
                    BorderLayout.EAST
                )
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        )

        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.isOpaque = false
        chrome.contentPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        chrome.contentPanel.add(detailsPanel)
        update(toolCall)
    }

    fun update(toolCall: AcpSessionService.ToolCallInfo) {
        titleLabel.text = "${toolKindDisplay(toolCall.kind)} ${toolCall.title}"
        statusLabel.updateStatus(toolCall.status)
        detailsPanel.removeAll()
        val details = buildList {
            toolCall.locations.firstOrNull()?.let { add(it) }
            toolCall.contentSummary?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        details.forEachIndexed { index, line ->
            detailsPanel.add(
                JBLabel(line).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = if (index == 0) JBUI.Borders.empty() else JBUI.Borders.emptyTop(2)
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        }
        revalidate()
        repaint()
    }
}

private class MessagePromptFooter(
    state: MessagePromptState,
    onCancel: () -> Unit
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val statusIcon = MessagePromptStatusIcon(state)
    private val cancelLink = ActionLink("Cancel").apply {
        addActionListener { onCancel() }
        toolTipText = "Cancel the current ACP response"
    }

    init {
        isOpaque = false
        add(statusIcon, BorderLayout.EAST)
        updateState(state)
    }

    fun updateState(state: MessagePromptState) {
        statusIcon.updateState(state)
        if (state == MessagePromptState.RUNNING) {
            if (cancelLink.parent != this) {
                add(cancelLink, BorderLayout.WEST)
            }
        } else if (cancelLink.parent == this) {
            remove(cancelLink)
        }
        revalidate()
        repaint()
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
    private var shouldAnimate = state == MessagePromptState.RUNNING
    private var animationFrame = 0

    init {
        isOpaque = false
        if (shouldAnimate) {
            animationTimer.addActionListener {
                animationFrame = (animationFrame + 1) % animatedIcons.size
                icon = animatedIcons[animationFrame]
                repaint()
            }
            animationTimer.isRepeats = true
        }
        updateState(state)
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

    fun updateState(state: MessagePromptState) {
        shouldAnimate = state == MessagePromptState.RUNNING
        if (shouldAnimate) {
            animationFrame = 0
            icon = animatedIcons[animationFrame]
            if (isDisplayable && !animationTimer.isRunning) {
                animationTimer.start()
            }
        } else {
            animationTimer.stop()
            icon = when (state) {
                MessagePromptState.RUNNING -> animatedIcons.first()
                MessagePromptState.COMPLETED -> AllIcons.General.InspectionsOK
                MessagePromptState.WARNING -> AllIcons.General.Warning
            }
        }
        repaint()
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
private fun renderHtml(text: String): String {
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

    fun updateStatus(status: String) {
        statusText.text = status.toDisplayLabel()
        statusIcon.updateStatus(status)
        revalidate()
        repaint()
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
    private var shouldAnimate = status == "in_progress"
    private var animationFrame = 0

    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        if (shouldAnimate) {
            animationTimer.addActionListener {
                animationFrame = (animationFrame + 1) % animatedIcons.size
                icon = animatedIcons[animationFrame]
                repaint()
            }
            animationTimer.isRepeats = true
        }
        updateStatus(status)
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

    fun updateStatus(status: String) {
        shouldAnimate = status == "in_progress"
        if (shouldAnimate) {
            animationFrame = 0
            icon = animatedIcons[animationFrame]
            if (isDisplayable && !animationTimer.isRunning) {
                animationTimer.start()
            }
        } else {
            animationTimer.stop()
            icon = statusIconFor(status)
        }
        repaint()
    }
}

private sealed interface MessageEntryView {
    val component: JComponent
    fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean
    fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean)
}

private class MarkdownEntryView(private var currentText: String, private val markdownPane: MarkdownPane) : MessageEntryView {
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

private class ThoughtEntryView(
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

private class ToolCallEntryView(private val toolCallRow: ToolCallRow) : MessageEntryView {
    override val component: JComponent
        get() = toolCallRow

    override fun canUpdate(entry: AcpSessionService.MessageEntry): Boolean =
        entry is AcpSessionService.MessageEntry.ToolCall

    override fun update(entry: AcpSessionService.MessageEntry, thoughtExpanded: Boolean) {
        entry as AcpSessionService.MessageEntry.ToolCall
        toolCallRow.update(entry.toolCall)
    }
}

private class PermissionRequestEntryView(
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
