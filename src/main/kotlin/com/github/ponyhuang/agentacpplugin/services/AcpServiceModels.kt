package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

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

interface AcpAgentService : Disposable {
    val descriptor: AcpAgentDescriptor
    val sessionUpdates: SharedFlow<SessionUpdate>
    val isConnected: Boolean

    suspend fun connect()

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
        sessionUpdateSink: suspend (SessionUpdate) -> Unit,
    ): AcpRuntimeConnection
}

internal interface AcpRuntimeConnection {
    val descriptor: AcpAgentDescriptor
    val client: Client
    val session: ClientSession
    val agentInfo: AgentInfo

    suspend fun close()
}
