package com.github.ponyhuang.agentacpplugin.toolwindow

sealed interface ToolWindowConversationItem {
    val itemId: String

    data class UserText(
        override val itemId: String,
        val text: String,
    ) : ToolWindowConversationItem

    data class AssistantText(
        override val itemId: String,
        val text: String,
    ) : ToolWindowConversationItem

    data class Thinking(
        override val itemId: String,
        val text: String,
    ) : ToolWindowConversationItem

    data class ToolCall(
        override val itemId: String,
        val title: String,
        val status: String?,
        val details: String?,
    ) : ToolWindowConversationItem

    data class SystemStatus(
        override val itemId: String,
        val text: String,
    ) : ToolWindowConversationItem

    data class Error(
        override val itemId: String,
        val text: String,
    ) : ToolWindowConversationItem
}

enum class ToolWindowComposerState {
    IDLE,
    CONNECTING,
    SENDING,
}
