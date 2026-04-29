package github.ponyhuang.acpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ChatViewPanel(
    project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {
    private val logger = Logger.getInstance(ChatViewPanel::class.java)

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toolWindowProject = project
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
            val latestAssistantMessageId = state.messages.lastOrNull { it.role == "assistant" }?.id
            val visibleMessages = state.messages.filter { message ->
                message.hasRenderableContent(
                    isLatestAssistantMessage = message.id == latestAssistantMessageId,
                    isLoading = state.isLoading
                )
            }
            val showEmptyState = visibleMessages.isEmpty() && !state.isLoading
            val renderMode = when {
                showEmptyState -> "empty_state"
                visibleMessages.isEmpty() -> "loading_without_messages"
                else -> "message_list"
            }
            logger.info(
                "[ChatRender] render requested: totalMessages=${state.messages.size}, visibleMessages=${visibleMessages.size}, " +
                    "isLoading=${state.isLoading}, lastStopReason=${state.lastStopReason}, mode=$renderMode"
            )
            state.messages.lastOrNull { it.role == "assistant" }?.let { latestAssistant ->
                logger.info(
                    "[ChatRender] latest assistant snapshot: id=${latestAssistant.id}, contentLength=${latestAssistant.content.length}, " +
                        "thoughtLength=${latestAssistant.thought?.length ?: 0}, entries=${latestAssistant.entries.size}, " +
                        "toolCalls=${latestAssistant.toolCalls.size}, renderable=${latestAssistant.hasRenderableContent(true, state.isLoading)}"
                )
            }

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
            showEmptyState -> rebuildPanelChildren(emptyList(), showEmptyState = true)
            canKeepChildOrder -> Unit
            canAppendOnly -> {
                appendRows(nextIds.drop(previousIds.size), startRow = previousIds.size)
                ensureSpacerAtRow(nextIds.size)
            }
            else -> rebuildPanelChildren(nextIds, showEmptyState = false)
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
            project = toolWindowProject,
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
                JBLabel(MyBundle.message("chat.emptyTitle")).apply {
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.emptyBottom(6)
                    alignmentX = LEFT_ALIGNMENT
                }
            )
            add(
                JBLabel(MyBundle.message("chat.emptyDescription")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        }
    }

    override fun dispose() {
        messageRowControllers.values.forEach { it.dispose() }
        messageRowControllers.clear()
        permissionCardsByRequestId.clear()
        smartScroller.dispose()
        uiScope.cancel()
    }
}
