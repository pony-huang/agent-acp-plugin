package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
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
import com.intellij.util.animation.Easing
import com.intellij.util.animation.JBAnimator
import com.intellij.util.animation.animation
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
                sessionService.pendingPermissionRequests
            ) { messages, isLoading, pendingPermissionRequests ->
                ConversationViewState(
                    messages = messages,
                    isLoading = isLoading,
                    pendingPermissionRequests = pendingPermissionRequests
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

            val shouldStickToBottom = isNearBottom()

            expandedThoughts.retainAll(state.messages.map { it.id }.toSet())
            messagePanel.removeAll()
            if (state.messages.isEmpty() && !state.isLoading) {
                addMessageRow(createEmptyState(), 0, false)
                addMessageSpacer(1)
            } else {
                var rowIndex = 0
                state.messages.forEach { message ->
                    addMessageRow(
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
                        ),
                        rowIndex++
                    )
                }
                state.pendingPermissionRequests.forEach { request ->
                    addMessageRow(
                        PermissionRequestCardPanel(
                            request = request,
                            onSubmit = { optionId ->
                                uiScope.launch {
                                    sessionService.submitPermissionRequest(request.requestId, optionId)
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

            if (shouldStickToBottom) {
                scrollToBottom()
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
    val pendingPermissionRequests: List<AcpSessionService.PermissionRequestInfo>
)

private class MessageCardPanel(
    val message: AcpSessionService.ChatMessage,
    thoughtExpanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        isOpaque = true
        background = backgroundForRole(message.role)
        border = JBUI.Borders.empty(10)

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
    private val iconAnimator = JBAnimator().apply {
        period = 60
        isCyclic = true
        type = JBAnimator.Type.EACH_FRAME
    }

    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        icon = statusIconFor(status)
        if (status == "in_progress") {
            iconAnimator.animate(animation(animatedIcons, ::setIcon).apply {
                duration = iconAnimator.period * animatedIcons.size
                easing = Easing.LINEAR
            })
        }
    }

    override fun removeNotify() {
        iconAnimator.stop()
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
        "failed" -> AllIcons.General.Error
        else -> AllIcons.General.Information
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

private fun buildPermissionOptionLabel(option: AcpSessionService.PermissionOptionInfo): String {
    val parts = buildList {
        add(option.label)
        option.kind?.takeIf { it.isNotBlank() }?.let { add(it.replace('_', ' ')) }
    }
    return parts.joinToString(" • ")
}
