package com.github.ponyhuang.agentacpplugin.services.session

import java.time.Instant

enum class SessionStatus {
    IDLE,
    STREAMING,
    DEGRADED,
    CLOSED,
}

data class ConversationSessionState(
    val sessionId: String,
    val endpointId: String,
    val title: String = "New Session",
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val turns: List<ConversationTurnState> = emptyList(),
    val selectedTurnId: String? = null,
    val lastVisibleSnapshotAt: Instant? = null,
    val unreadStreamingIndicator: Boolean = false,
    val currentModeId: String? = null,
    val usageSummary: String? = null,
    val availableCommands: List<String> = emptyList(),
    val configOptions: List<String> = emptyList(),
    val bannerMessage: String? = null,
)
