package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class AcpAgentDescriptor(
    val id: String,
    val displayName: String = id,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
) {
    val commandLine: List<String>
        get() = buildList {
            add(command)
            addAll(args)
        }
}

sealed interface AcpConnectionState {
    data object Idle : AcpConnectionState

    data class Connecting(
        val descriptor: AcpAgentDescriptor,
    ) : AcpConnectionState

    data class Connected(
        val descriptor: AcpAgentDescriptor,
        val sessionId: String,
        val agentInfo: AgentInfo,
    ) : AcpConnectionState

    data class Disconnected(
        val descriptor: AcpAgentDescriptor,
        val sessionId: String?,
        val reason: String? = null,
    ) : AcpConnectionState

    data class Failed(
        val descriptor: AcpAgentDescriptor,
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

interface AcpAgentService : Disposable {
    val descriptor: AcpAgentDescriptor
    val connectionState: StateFlow<AcpConnectionState>
    val events: SharedFlow<AcpServiceEvent>
    val isConnected: Boolean

    suspend fun connect(): AcpConnectionState.Connected

    fun sendPrompt(text: String): Flow<Event>

    suspend fun disconnect(reason: String? = null)
}

internal interface AcpAgentServiceFactory {
    fun create(
        project: Project,
        descriptor: AcpAgentDescriptor,
        parentScope: CoroutineScope,
        runtimeConnector: AcpRuntimeConnector = ProcessAcpRuntimeConnector,
    ): AcpAgentService
}

internal interface AcpRuntimeConnector {
    suspend fun connect(
        project: Project,
        scope: CoroutineScope,
        descriptor: AcpAgentDescriptor,
        eventSink: suspend (AcpServiceEvent) -> Unit,
    ): AcpRuntimeConnection
}

internal interface AcpRuntimeConnection {
    val descriptor: AcpAgentDescriptor
    val client: Client
    val session: ClientSession
    val agentInfo: AgentInfo

    suspend fun close()
}
