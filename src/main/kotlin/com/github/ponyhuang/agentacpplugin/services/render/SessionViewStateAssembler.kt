package com.github.ponyhuang.agentacpplugin.services.render

import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointState
import com.github.ponyhuang.agentacpplugin.services.session.ConversationSessionState
import com.github.ponyhuang.agentacpplugin.services.session.SessionStatus
import com.github.ponyhuang.agentacpplugin.services.session.ToolCallState

class SessionViewStateAssembler {
    fun assemble(
        endpoint: AgentEndpointState,
        session: ConversationSessionState,
        timeline: List<TimelineItem>,
        toolCalls: List<ToolCallState>,
    ): SessionViewState {
        val banner = when {
            session.bannerMessage != null -> BannerState(session.bannerMessage, session.sessionStatus == SessionStatus.DEGRADED)
            endpoint.connectionStatus == AgentConnectionStatus.FAILED && endpoint.lastErrorSummary != null -> BannerState(endpoint.lastErrorSummary, true)
            else -> null
        }
        return SessionViewState(
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
            composerEnabled = endpoint.connectionStatus == AgentConnectionStatus.CONNECTED && session.sessionStatus != SessionStatus.CLOSED,
            bannerState = banner,
            availableCommands = session.availableCommands,
            configOptions = session.configOptions,
        )
    }
}
