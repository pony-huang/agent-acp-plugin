package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

sealed interface AcpConnectionState {
    data object Idle : AcpConnectionState

    data class Connecting(
        val command: List<String>,
    ) : AcpConnectionState

    data class Connected(
        val command: List<String>,
        val sessionId: String,
        val agentInfo: AgentInfo,
    ) : AcpConnectionState

    data class Disconnected(
        val command: List<String>,
        val sessionId: String?,
        val reason: String? = null,
    ) : AcpConnectionState

    data class Failed(
        val command: List<String>,
        val message: String,
        val cause: Throwable? = null,
    ) : AcpConnectionState
}

sealed interface AcpServiceEvent {
    data class ConnectionStateChanged(
        val state: AcpConnectionState,
    ) : AcpServiceEvent

    data class SessionUpdateReceived(
        val update: SessionUpdate,
    ) : AcpServiceEvent

    data class PromptEvent(
        val event: Event,
    ) : AcpServiceEvent

    data class PromptFinished(
        val response: PromptResponse,
    ) : AcpServiceEvent

    data class PromptFailed(
        val prompt: String,
        val error: Throwable,
    ) : AcpServiceEvent

    data class PermissionAutoApproved(
        val toolCallTitle: String?,
        val option: PermissionOption,
    ) : AcpServiceEvent
}

internal interface AcpRuntimeConnector {
    suspend fun connect(
        project: Project,
        scope: CoroutineScope,
        command: List<String>,
        eventSink: suspend (AcpServiceEvent) -> Unit,
    ): AcpRuntimeConnection
}

internal interface AcpRuntimeConnection {
    val command: List<String>
    val client: Client
    val session: ClientSession
    val agentInfo: AgentInfo

    suspend fun close()
}
