package com.github.ponyhuang.agentacpplugin.services.session

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.protocol.Protocol
import java.lang.AutoCloseable
import java.util.concurrent.ConcurrentHashMap

data class RegisteredSession(
    val endpointId: String,
    val endpointName: String,
    val sessionId: SessionId,
    val client: Client,
    val protocol: Protocol,
    val session: ClientSession,
    val processHandle: AutoCloseable? = null,
)

class SessionRegistry {
    private val sessions = ConcurrentHashMap<String, RegisteredSession>()

    fun register(registeredSession: RegisteredSession) {
        sessions[registeredSession.sessionId.toString()] = registeredSession
    }

    fun get(sessionId: String): RegisteredSession? = sessions[sessionId]

    fun remove(sessionId: String): RegisteredSession? = sessions.remove(sessionId)

    fun all(): List<RegisteredSession> = sessions.values.sortedBy { it.endpointName }
}
