package github.ponyhuang.acpplugin.toolwindow.ui

import com.agentclientprotocol.model.StopReason
import github.ponyhuang.acpplugin.services.AcpSessionService

internal data class MessageRenderModel(
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

internal data class ConversationViewState(
    val messages: List<AcpSessionService.ChatMessage>,
    val isLoading: Boolean,
    val lastStopReason: StopReason?
)

internal fun AcpSessionService.ChatMessage.hasRenderableContent(
    isLatestAssistantMessage: Boolean,
    isLoading: Boolean
): Boolean {
    if (entries.isNotEmpty()) {
        return true
    }
    if (content.isNotBlank()) {
        return true
    }
    if (!thought.isNullOrBlank()) {
        return true
    }
    if (toolCalls.isNotEmpty()) {
        return true
    }
    return role == "assistant" && isLatestAssistantMessage && isLoading
}

internal fun AcpSessionService.ChatMessage.legacyRenderableEntries(): List<AcpSessionService.MessageEntry> {
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

internal fun List<AcpSessionService.ChatMessage>.toRenderModels(
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

