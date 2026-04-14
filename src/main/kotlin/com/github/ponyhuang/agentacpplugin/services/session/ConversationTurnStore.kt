package com.github.ponyhuang.agentacpplugin.services.session

import com.github.ponyhuang.agentacpplugin.services.render.RenderIntent
import com.github.ponyhuang.agentacpplugin.services.render.TimelineDisplayState
import com.github.ponyhuang.agentacpplugin.services.render.TimelineItem
import com.github.ponyhuang.agentacpplugin.services.render.TimelineItemType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConversationTurnStore {
    private val turnsBySession = ConcurrentHashMap<String, MutableList<ConversationTurnState>>()
    private val timelineBySession = ConcurrentHashMap<String, MutableList<TimelineItem>>()
    private val toolCallsBySession = ConcurrentHashMap<String, MutableMap<String, ToolCallState>>()
    private val externalTimelineIdsBySession = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun startTurn(sessionId: String, userPrompt: String): ConversationTurnState {
        val turn = ConversationTurnState(
            turnId = "turn-${UUID.randomUUID()}",
            sessionId = sessionId,
            userPromptSummary = userPrompt,
            turnStatus = TurnStatus.STREAMING,
        )
        turnsBySession.computeIfAbsent(sessionId) { mutableListOf() }.add(turn)
        appendTimelineItem(
            sessionId = sessionId,
            turnId = turn.turnId,
            itemType = TimelineItemType.USER_MESSAGE,
            text = userPrompt,
            title = "You",
            displayState = TimelineDisplayState.COMPLETED,
            externalId = null,
        )
        return turn
    }

    fun apply(sessionId: String, intents: List<RenderIntent>) {
        intents.forEach { apply(sessionId, it) }
    }

    fun apply(sessionId: String, intent: RenderIntent) {
        when (intent) {
            is RenderIntent.AppendTimeline -> {
                val activeTurn = activeTurn(sessionId) ?: startTurn(sessionId, "Background update")
                appendTimelineItem(
                    sessionId = sessionId,
                    turnId = activeTurn.turnId,
                    itemType = intent.itemType,
                    text = intent.text,
                    title = intent.title,
                    displayState = intent.displayState,
                    externalId = intent.externalId,
                    metadata = intent.metadata,
                )
            }

            is RenderIntent.UpsertToolCall -> {
                val activeTurn = activeTurn(sessionId) ?: startTurn(sessionId, "Tool activity")
                val current = toolCallsBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }[intent.toolCallId]
                val next = (current ?: ToolCallState(
                    toolCallId = intent.toolCallId,
                    turnId = activeTurn.turnId,
                    toolName = intent.title,
                    status = intent.status,
                    argumentSummary = intent.argumentSummary,
                )).copy(
                    toolName = intent.title,
                    status = intent.status,
                    argumentSummary = intent.argumentSummary ?: current?.argumentSummary,
                    resultSummary = intent.resultSummary ?: current?.resultSummary,
                    failureSummary = intent.failureSummary ?: current?.failureSummary,
                    finishedAt = if (intent.status == ToolLifecycleStatus.SUCCEEDED || intent.status == ToolLifecycleStatus.FAILED) Instant.now() else null,
                )
                toolCallsBySession.getValue(sessionId)[intent.toolCallId] = next
            }

            is RenderIntent.MarkTurnCompleted -> finishTurn(sessionId, intent.reason)
            is RenderIntent.MarkTurnFailed -> failTurn(sessionId, intent.message)
            is RenderIntent.MarkTurnStreaming -> markTurnStreaming(sessionId)
            is RenderIntent.SetBanner,
            is RenderIntent.UpdateAvailableCommands,
            is RenderIntent.UpdateConfigOptions,
            is RenderIntent.UpdateCurrentMode,
            is RenderIntent.UpdateSessionTitle,
            is RenderIntent.UpdateUsage,
            -> Unit
        }
    }

    fun activeTurn(sessionId: String): ConversationTurnState? =
        turnsBySession[sessionId]?.lastOrNull { it.turnStatus == TurnStatus.STREAMING || it.turnStatus == TurnStatus.QUEUED }

    fun turns(sessionId: String): List<ConversationTurnState> = turnsBySession[sessionId].orEmpty().toList()

    fun timeline(sessionId: String): List<TimelineItem> = timelineBySession[sessionId].orEmpty().toList()

    fun toolCalls(sessionId: String): List<ToolCallState> = toolCallsBySession[sessionId].orEmpty().values.sortedBy { it.startedAt }

    private fun appendTimelineItem(
        sessionId: String,
        turnId: String,
        itemType: TimelineItemType,
        text: String,
        title: String?,
        displayState: TimelineDisplayState,
        externalId: String?,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val items = timelineBySession.computeIfAbsent(sessionId) { mutableListOf() }
        val externalIds = externalTimelineIdsBySession.computeIfAbsent(sessionId) { ConcurrentHashMap() }
        val existingItemId = externalId?.let { externalIds[it] }
        if (existingItemId != null) {
            val index = items.indexOfFirst { it.itemId == existingItemId }
            if (index >= 0) {
                val current = items[index]
                items[index] = current.copy(
                    itemType = itemType,
                    title = title ?: current.title,
                    textContent = if (itemType.appendable && current.textContent.isNotBlank()) current.textContent + text else text,
                    displayState = displayState,
                    updatedAt = Instant.now(),
                    metadata = current.metadata + metadata,
                )
                return
            }
        }

        val item = TimelineItem(
            itemId = "item-${UUID.randomUUID()}",
            turnId = turnId,
            itemType = itemType,
            displayState = displayState,
            sequenceNumber = items.size,
            title = title,
            textContent = text,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            metadata = metadata,
        )
        items.add(item)
        if (externalId != null) {
            externalIds[externalId] = item.itemId
        }
        val turns = turnsBySession.computeIfAbsent(sessionId) { mutableListOf() }
        val lastIndex = turns.indexOfLast { it.turnId == turnId }
        if (lastIndex >= 0) {
            turns[lastIndex] = turns[lastIndex].copy(timelineItemIds = turns[lastIndex].timelineItemIds + item.itemId)
        }
    }

    private fun markTurnStreaming(sessionId: String) {
        val turns = turnsBySession[sessionId] ?: return
        val lastIndex = turns.indexOfLast { it.turnStatus == TurnStatus.QUEUED || it.turnStatus == TurnStatus.STREAMING }
        if (lastIndex >= 0) {
            turns[lastIndex] = turns[lastIndex].copy(turnStatus = TurnStatus.STREAMING)
        }
    }

    private fun finishTurn(sessionId: String, reason: TurnCompletionReason) {
        val turns = turnsBySession[sessionId] ?: return
        val lastIndex = turns.indexOfLast { it.turnStatus == TurnStatus.QUEUED || it.turnStatus == TurnStatus.STREAMING }
        if (lastIndex >= 0) {
            turns[lastIndex] = turns[lastIndex].copy(
                turnStatus = if (reason == TurnCompletionReason.CANCELLED) TurnStatus.INTERRUPTED else TurnStatus.COMPLETED,
                completedAt = Instant.now(),
                completionReason = reason,
            )
        }
        timelineBySession[sessionId]?.replaceAll { item ->
            if (item.displayState == TimelineDisplayState.IN_PROGRESS) {
                item.copy(displayState = TimelineDisplayState.COMPLETED, updatedAt = Instant.now())
            } else {
                item
            }
        }
    }

    private fun failTurn(sessionId: String, message: String) {
        val turns = turnsBySession[sessionId] ?: return
        val lastIndex = turns.indexOfLast { it.turnStatus == TurnStatus.QUEUED || it.turnStatus == TurnStatus.STREAMING }
        if (lastIndex >= 0) {
            turns[lastIndex] = turns[lastIndex].copy(
                turnStatus = TurnStatus.FAILED,
                completedAt = Instant.now(),
                completionReason = TurnCompletionReason.ERROR,
            )
        }
        val turnId = activeTurn(sessionId)?.turnId ?: turns.lastOrNull()?.turnId ?: return
        appendTimelineItem(
            sessionId = sessionId,
            turnId = turnId,
            itemType = TimelineItemType.STATUS,
            text = message,
            title = "Status",
            displayState = TimelineDisplayState.FAILED,
            externalId = null,
        )
    }
}
