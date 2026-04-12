package com.github.ponyhuang.agentacpplugin.services.render

import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointState
import com.github.ponyhuang.agentacpplugin.services.session.ConversationSessionState
import com.github.ponyhuang.agentacpplugin.services.session.ToolCallState
import java.util.concurrent.ConcurrentHashMap

class SessionViewSnapshotStore {
    private val snapshots = ConcurrentHashMap<String, SessionViewSnapshot>()

    fun rebuild(
        endpoint: AgentEndpointState,
        session: ConversationSessionState,
        timeline: List<TimelineItem>,
        toolCalls: List<ToolCallState>,
    ): SessionViewSnapshot {
        val banner = when {
            session.bannerMessage != null -> BannerState(session.bannerMessage, session.sessionStatus.name == "DEGRADED")
            endpoint.connectionStatus == AgentConnectionStatus.FAILED && endpoint.lastErrorSummary != null -> BannerState(endpoint.lastErrorSummary, true)
            else -> null
        }
        val snapshot = SessionViewSnapshot(
            sessionId = session.sessionId,
            headerState = SessionHeaderState(
                title = session.title,
                connectionStatus = endpoint.connectionStatus.name,
                sessionStatus = session.sessionStatus.name,
                currentMode = session.currentModeId,
                usageSummary = session.usageSummary,
            ),
            visibleTimeline = timeline.sortedBy { it.sequenceNumber }.map { item ->
                if (item.itemType == TimelineItemType.TOOL_CALL || item.itemType == TimelineItemType.TOOL_RESULT) {
                    val toolId = item.metadata["toolCallId"] ?: item.title
                    val state = toolCalls.firstOrNull { it.toolName == item.title || it.toolCallId == toolId }
                    if (state != null) {
                        item.copy(
                            metadata = item.metadata + mapOf(
                                "toolStatus" to state.status.name,
                                "toolResult" to (state.resultSummary ?: ""),
                            ),
                        )
                    } else {
                        item
                    }
                } else {
                    item
                }
            },
            composerEnabled = endpoint.connectionStatus == AgentConnectionStatus.CONNECTED && session.sessionStatus != com.github.ponyhuang.agentacpplugin.services.session.SessionStatus.CLOSED,
            bannerState = banner,
            availableCommands = session.availableCommands,
            configOptions = session.configOptions,
        )
        snapshots[session.sessionId] = snapshot
        return snapshot
    }

    fun get(sessionId: String): SessionViewSnapshot? = snapshots[sessionId]

    fun all(): Map<String, SessionViewSnapshot> = snapshots.toMap()
}
