package com.github.ponyhuang.agentacpplugin.services.session

import java.time.Instant

enum class TurnStatus {
    QUEUED,
    STREAMING,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}

enum class TurnCompletionReason {
    END_TURN,
    CANCELLED,
    DISCONNECTED,
    ERROR,
    UNKNOWN,
}

data class ConversationTurnState(
    val turnId: String,
    val sessionId: String,
    val userPromptSummary: String,
    val turnStatus: TurnStatus = TurnStatus.QUEUED,
    val timelineItemIds: List<String> = emptyList(),
    val startedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val completionReason: TurnCompletionReason? = null,
)
