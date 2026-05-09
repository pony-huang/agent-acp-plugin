package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.services.AcpSessionService

internal class ConversationRenderStateMapper {
    fun map(
        state: ConversationViewState,
        expandedThoughtIds: Set<String>
    ): ConversationRenderState {
        val latestAssistantMessage = state.messages.lastOrNull { it.role == ASSISTANT_ROLE }
        val visibleMessages = state.messages.filter { message ->
            message.hasRenderableContent(
                isLatestAssistantMessage = message.id == latestAssistantMessage?.id,
                isLoading = state.isLoading
            )
        }
        val showEmptyState = visibleMessages.isEmpty() && !state.isLoading
        return ConversationRenderState(
            allMessages = state.messages,
            visibleMessages = visibleMessages,
            renderModels = visibleMessages.toRenderModels(
                isLoading = state.isLoading,
                lastStopReason = state.lastStopReason,
                expandedThoughts = expandedThoughtIds
            ),
            latestAssistantMessage = latestAssistantMessage,
            showEmptyState = showEmptyState,
            renderMode = when {
                showEmptyState -> ConversationRenderMode.EMPTY_STATE
                visibleMessages.isEmpty() -> ConversationRenderMode.LOADING_WITHOUT_MESSAGES
                else -> ConversationRenderMode.MESSAGE_LIST
            }
        )
    }

    companion object {
        internal const val ASSISTANT_ROLE = "assistant"
    }
}

internal data class ConversationRenderState(
    val allMessages: List<AcpSessionService.ChatMessage>,
    val visibleMessages: List<AcpSessionService.ChatMessage>,
    val renderModels: List<MessageRenderModel>,
    val latestAssistantMessage: AcpSessionService.ChatMessage?,
    val showEmptyState: Boolean,
    val renderMode: ConversationRenderMode
)

internal enum class ConversationRenderMode {
    EMPTY_STATE,
    LOADING_WITHOUT_MESSAGES,
    MESSAGE_LIST
}
