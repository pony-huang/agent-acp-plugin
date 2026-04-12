package com.github.ponyhuang.agentacpplugin.services.session

import java.time.Instant

enum class ToolLifecycleStatus {
    REQUESTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class ToolCallState(
    val toolCallId: String,
    val turnId: String,
    val toolName: String,
    val status: ToolLifecycleStatus = ToolLifecycleStatus.REQUESTED,
    val argumentSummary: String? = null,
    val resultSummary: String? = null,
    val failureSummary: String? = null,
    val startedAt: Instant = Instant.now(),
    val finishedAt: Instant? = null,
)
