package com.github.ponyhuang.agentacpplugin.services.render

import com.github.ponyhuang.agentacpplugin.services.session.ToolLifecycleStatus
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason

sealed interface RenderIntent {
    data class AppendTimeline(
        val itemType: TimelineItemType,
        val text: String,
        val displayState: TimelineDisplayState,
        val externalId: String? = null,
        val title: String? = null,
        val metadata: Map<String, String> = emptyMap(),
    ) : RenderIntent

    data class UpsertToolCall(
        val toolCallId: String,
        val title: String,
        val status: ToolLifecycleStatus,
        val argumentSummary: String? = null,
        val resultSummary: String? = null,
        val failureSummary: String? = null,
    ) : RenderIntent

    data class UpdateAvailableCommands(val commands: List<String>) : RenderIntent

    data class UpdateConfigOptions(val options: List<String>) : RenderIntent

    data class UpdateSessionTitle(val title: String?) : RenderIntent

    data class UpdateCurrentMode(val modeId: String) : RenderIntent

    data class UpdateUsage(val usageSummary: String) : RenderIntent

    data class SetBanner(val message: String?) : RenderIntent

    data object MarkTurnStreaming : RenderIntent

    data class MarkTurnCompleted(val reason: TurnCompletionReason) : RenderIntent

    data class MarkTurnFailed(val message: String) : RenderIntent
}
