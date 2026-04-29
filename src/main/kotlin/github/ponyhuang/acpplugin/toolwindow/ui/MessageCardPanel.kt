package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Timer

internal class MessageCardPanel(
    private val project: com.intellij.openapi.project.Project,
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

        val nextEntries = visibleEntries(message.entries.ifEmpty { message.legacyRenderableEntries() })
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

    private fun visibleEntries(
        entries: List<AcpSessionService.MessageEntry>
    ): List<AcpSessionService.MessageEntry> {
        val hideContent = entries.any { entry ->
            entry is AcpSessionService.MessageEntry.ToolCall && entry.toolCall.kind == "edit"
        }
        if (!hideContent) {
            return entries
        }
        return entries.filterNot { entry -> entry is AcpSessionService.MessageEntry.Content }
    }

    private fun rebuildBody(
        entries: List<AcpSessionService.MessageEntry>,
        thoughtExpanded: Boolean,
        onThoughtToggled: (Boolean) -> Unit
    ) {
        entryViews.forEach(MessageEntryView::dispose)
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
                ToolCallEntryView(ToolCallRow(project, entry.toolCall))
        }
    }

    fun dispose() {
        entryViews.forEach(MessageEntryView::dispose)
        entryViews.clear()
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

internal class MessagePromptFooter(
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

internal enum class MessagePromptState {
    RUNNING,
    COMPLETED,
    WARNING
}

internal class MessagePromptStatusIcon(state: MessagePromptState) : JBLabel() {
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
