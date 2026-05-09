package github.ponyhuang.acpplugin.toolwindow.ui.chat

import com.agentclientprotocol.model.StopReason
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.ui.AnimatedStatusLabel
import github.ponyhuang.acpplugin.toolwindow.ui.ProcessStepIcons
import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JPanel

internal enum class MessagePromptState {
    RUNNING,
    COMPLETED,
    WARNING
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

internal class MessagePromptFooter(
    state: MessagePromptState,
    onCancel: () -> Unit
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val statusIcon = MessagePromptStatusIcon(state)
    private val cancelLink = ActionLink(MyBundle.message("chat.prompt.cancel")).apply {
        addActionListener { onCancel() }
        toolTipText = MyBundle.message("chat.prompt.cancelTooltip")
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

internal class MessagePromptStatusIcon(state: MessagePromptState) : AnimatedStatusLabel(ProcessStepIcons.icons) {
    private var currentState: MessagePromptState = state

    init {
        updateState(state)
    }

    override fun staticIcon(): Icon = when (currentState) {
        MessagePromptState.RUNNING -> ProcessStepIcons.icons.first()
        MessagePromptState.COMPLETED -> AllIcons.General.InspectionsOK
        MessagePromptState.WARNING -> AllIcons.General.Warning
    }

    fun updateState(state: MessagePromptState) {
        currentState = state
        updateAnimation(state == MessagePromptState.RUNNING)
    }
}

