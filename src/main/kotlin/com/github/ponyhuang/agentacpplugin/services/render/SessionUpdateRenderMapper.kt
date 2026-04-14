package com.github.ponyhuang.agentacpplugin.services.render

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.session.ToolLifecycleStatus
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason

class SessionUpdateRenderMapper(
    private val contentBlockRenderer: ContentBlockRenderer = ContentBlockRenderer(),
) {
    @OptIn(UnstableApi::class)
    fun map(update: SessionUpdate): List<RenderIntent> {
        return when (update) {
            is SessionUpdate.AgentMessageChunk -> listOf(
                RenderIntent.MarkTurnStreaming,
                RenderIntent.AppendTimeline(
                    itemType = TimelineItemType.FINAL_MESSAGE,
                    text = contentBlockRenderer.render(update.content),
                    displayState = TimelineDisplayState.IN_PROGRESS,
                    externalId = update.messageId?.toString(),
                    title = "Assistant",
                ),
            )

            is SessionUpdate.AgentThoughtChunk -> listOf(
                RenderIntent.MarkTurnStreaming,
                RenderIntent.AppendTimeline(
                    itemType = TimelineItemType.THOUGHT,
                    text = contentBlockRenderer.render(update.content),
                    displayState = TimelineDisplayState.IN_PROGRESS,
                    externalId = update.messageId?.toString(),
                    title = "Thought",
                ),
            )

            is SessionUpdate.UserMessageChunk -> listOf(
                RenderIntent.AppendTimeline(
                    itemType = TimelineItemType.USER_MESSAGE,
                    text = contentBlockRenderer.render(update.content),
                    displayState = TimelineDisplayState.COMPLETED,
                    externalId = update.messageId?.toString(),
                    title = "You",
                ),
            )

            is SessionUpdate.ToolCall -> listOf(
                RenderIntent.MarkTurnStreaming,
                RenderIntent.UpsertToolCall(
                    toolCallId = update.toolCallId.toString(),
                    title = update.title,
                    status = toToolLifecycleStatus(update.status?.name),
                    argumentSummary = update.rawInput?.toString(),
                    resultSummary = update.rawOutput?.toString(),
                ),
                RenderIntent.AppendTimeline(
                    itemType = TimelineItemType.TOOL_CALL,
                    text = renderToolDetails(update.title, update.status?.name, update.content.map { it.toString() }),
                    displayState = toTimelineState(update.status?.name),
                    externalId = update.toolCallId.toString(),
                    title = update.title,
                    metadata = mapOf("kind" to (update.kind?.name ?: "unknown")),
                ),
            )

            is SessionUpdate.ToolCallUpdate -> listOf(
                RenderIntent.MarkTurnStreaming,
                RenderIntent.UpsertToolCall(
                    toolCallId = update.toolCallId.toString(),
                    title = update.title ?: "Tool",
                    status = toToolLifecycleStatus(update.status?.name),
                    argumentSummary = update.rawInput?.toString(),
                    resultSummary = update.rawOutput?.toString(),
                    failureSummary = if (update.status?.name?.contains("FAIL", ignoreCase = true) == true) update.rawOutput?.toString() else null,
                ),
                RenderIntent.AppendTimeline(
                    itemType = if (update.status?.name?.contains("SUCCESS", ignoreCase = true) == true || update.status?.name?.contains("FAIL", ignoreCase = true) == true) {
                        TimelineItemType.TOOL_RESULT
                    } else {
                        TimelineItemType.TOOL_CALL
                    },
                    text = renderToolDetails(update.title ?: "Tool", update.status?.name, update.content?.map { it.toString() }.orEmpty()),
                    displayState = toTimelineState(update.status?.name),
                    externalId = update.toolCallId.toString(),
                    title = update.title ?: "Tool",
                    metadata = buildMap {
                        update.kind?.name?.let { put("kind", it) }
                    },
                ),
            )

            is SessionUpdate.PlanUpdate -> listOf(
                RenderIntent.AppendTimeline(
                    itemType = TimelineItemType.STATUS,
                    text = update.entries.joinToString(separator = "\n") { it.toString() },
                    displayState = TimelineDisplayState.IN_PROGRESS,
                    title = "Plan",
                    metadata = mapOf("panel" to "plan"),
                ),
            )

            is SessionUpdate.AvailableCommandsUpdate -> listOf(
                RenderIntent.UpdateAvailableCommands(
                    update.availableCommands.map { command ->
                        if (command.input != null) {
                            "/${command.name} - ${command.description}"
                        } else {
                            "/${command.name}"
                        }
                    },
                ),
            )

            is SessionUpdate.CurrentModeUpdate -> listOf(RenderIntent.UpdateCurrentMode(update.currentModeId.toString()))

            is SessionUpdate.ConfigOptionUpdate -> listOf(
                RenderIntent.UpdateConfigOptions(update.configOptions.map { it.toString() }),
            )

            is SessionUpdate.SessionInfoUpdate -> listOf(RenderIntent.UpdateSessionTitle(update.title))

            is SessionUpdate.UsageUpdate -> listOf(
                RenderIntent.UpdateUsage("Used ${update.used} / ${update.size}${update.cost?.let { " (${it.amount} ${it.currency})" } ?: ""}"),
            )

            is SessionUpdate.UnknownSessionUpdate -> listOf(
                RenderIntent.SetBanner("Unknown update: ${update.sessionUpdateType}"),
                RenderIntent.AppendTimeline(
                    itemType = TimelineItemType.STATUS,
                    text = "Unknown session update `${update.sessionUpdateType}`",
                    displayState = TimelineDisplayState.FAILED,
                    title = "Protocol",
                ),
            )
        }
    }

    fun promptFinished(reason: TurnCompletionReason): List<RenderIntent> = listOf(RenderIntent.MarkTurnCompleted(reason))

    fun promptFailed(message: String): List<RenderIntent> = listOf(RenderIntent.MarkTurnFailed(message))

    private fun renderToolDetails(title: String, status: String?, content: List<String>): String {
        val contentText = if (content.isEmpty()) "" else "\n" + content.joinToString(separator = "\n")
        return buildString {
            append(title)
            status?.let {
                append(" [")
                append(it)
                append("]")
            }
            append(contentText)
        }
    }

    private fun toToolLifecycleStatus(status: String?): ToolLifecycleStatus {
        return when {
            status == null -> ToolLifecycleStatus.REQUESTED
            status.contains("SUCCESS", ignoreCase = true) -> ToolLifecycleStatus.SUCCEEDED
            status.contains("FAIL", ignoreCase = true) || status.contains("ERROR", ignoreCase = true) -> ToolLifecycleStatus.FAILED
            status.contains("RUN", ignoreCase = true) || status.contains("EXEC", ignoreCase = true) -> ToolLifecycleStatus.RUNNING
            else -> ToolLifecycleStatus.REQUESTED
        }
    }

    private fun toTimelineState(status: String?): TimelineDisplayState {
        return when (toToolLifecycleStatus(status)) {
            ToolLifecycleStatus.REQUESTED,
            ToolLifecycleStatus.RUNNING,
            -> TimelineDisplayState.IN_PROGRESS

            ToolLifecycleStatus.SUCCEEDED -> TimelineDisplayState.COMPLETED
            ToolLifecycleStatus.FAILED -> TimelineDisplayState.FAILED
        }
    }
}
