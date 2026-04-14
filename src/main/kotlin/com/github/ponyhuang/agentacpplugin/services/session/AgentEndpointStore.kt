package com.github.ponyhuang.agentacpplugin.services.session

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class AgentEndpointStore {
    private val endpoints = ConcurrentHashMap<String, AgentEndpointState>()
    private val sessions = ConcurrentHashMap<String, ConversationSessionState>()

    fun createEndpoint(endpointId: String, displayName: String): AgentEndpointState {
        val state = AgentEndpointState(endpointId = endpointId, displayName = displayName)
        endpoints[endpointId] = state
        return state
    }

    fun updateEndpoint(
        endpointId: String,
        status: AgentConnectionStatus,
        capabilitiesSummary: String? = null,
        errorSummary: String? = null,
    ): AgentEndpointState {
        val current = endpoints.getValue(endpointId)
        val updated = current.copy(
            connectionStatus = status,
            capabilitiesSummary = capabilitiesSummary ?: current.capabilitiesSummary,
            lastErrorSummary = errorSummary,
        )
        endpoints[endpointId] = updated
        return updated
    }

    fun createSession(endpointId: String, sessionId: String, title: String): ConversationSessionState {
        val session = ConversationSessionState(sessionId = sessionId, endpointId = endpointId, title = title, lastActivityAt = Instant.now())
        sessions[sessionId] = session
        val endpoint = endpoints.getValue(endpointId)
        endpoints[endpointId] = endpoint.copy(activeSessionIds = endpoint.activeSessionIds + sessionId)
        return session
    }

    fun updateSession(sessionId: String, update: (ConversationSessionState) -> ConversationSessionState): ConversationSessionState {
        val next = update(sessions.getValue(sessionId)).copy(lastActivityAt = Instant.now())
        sessions[sessionId] = next
        return next
    }

    fun removeSession(sessionId: String) {
        val current = sessions.remove(sessionId) ?: return
        val endpoint = endpoints[current.endpointId] ?: return
        endpoints[current.endpointId] = endpoint.copy(activeSessionIds = endpoint.activeSessionIds - sessionId)
    }

    fun getEndpoint(endpointId: String): AgentEndpointState? = endpoints[endpointId]

    fun getSession(sessionId: String): ConversationSessionState? = sessions[sessionId]

    fun allEndpoints(): List<AgentEndpointState> = endpoints.values.sortedBy { it.displayName }

    fun allSessions(): List<ConversationSessionState> = sessions.values.sortedByDescending { it.lastActivityAt }
}
