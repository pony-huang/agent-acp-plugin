package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object DefaultAcpAgentServiceFactory : AcpAgentServiceFactory {
    override fun create(
        project: Project,
        descriptor: AcpAgentDescriptor,
        parentScope: CoroutineScope,
        runtimeConnector: AcpRuntimeConnector,
    ): AcpAgentService {
        return DefaultAcpAgentService(project, descriptor, parentScope, runtimeConnector)
    }
}

internal class DefaultAcpAgentService(
    private val project: Project,
    override val descriptor: AcpAgentDescriptor,
    parentScope: CoroutineScope,
    private val runtimeConnector: AcpRuntimeConnector,
) : AcpAgentService {

    private val logger = Logger.getInstance(DefaultAcpAgentService::class.java)
    private val serviceScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO + CoroutineName("AcpAgentService:${descriptor.id}")
    )
    private val lifecycleMutex = Mutex()
    private val promptMutex = Mutex()

    private val _connectionState = MutableStateFlow<AcpConnectionState>(AcpConnectionState.Idle)
    private val _events = MutableSharedFlow<AcpServiceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var runtimeConnection: AcpRuntimeConnection? = null

    @Volatile
    private var disposed = false

    override val connectionState: StateFlow<AcpConnectionState> = _connectionState.asStateFlow()
    override val events: SharedFlow<AcpServiceEvent> = _events.asSharedFlow()
    override val isConnected: Boolean
        get() = connectionState.value is AcpConnectionState.Connected

    override suspend fun connect(): AcpConnectionState.Connected = lifecycleMutex.withLock {
        ensureUsable()

        val existing = runtimeConnection
        if (existing != null) {
            return connectedState(existing)
        }

        emitState(AcpConnectionState.Connecting(descriptor))
        return try {
            val connection = runtimeConnector.connect(project, serviceScope, descriptor, ::emitEvent)
            runtimeConnection = connection
            connectedState(connection).also { emitState(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            logger.warn("Failed to connect ACP agent ${descriptor.displayName} for project ${project.name}", t)
            emitState(
                AcpConnectionState.Failed(
                    descriptor = descriptor,
                    message = t.message ?: "Failed to connect ACP agent ${descriptor.displayName}",
                    cause = t,
                )
            )
            throw t
        }
    }

    override fun sendPrompt(text: String): Flow<Event> = flow {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "Prompt text must not be empty" }

        val connection = lifecycleMutex.withLock {
            ensureUsable()
            runtimeConnection ?: error("ACP agent ${descriptor.displayName} is not connected")
        }

        try {
            promptMutex.withLock {
                connection.session.prompt(listOf(ContentBlock.Text(prompt))).collect { event ->
                    emitEvent(AcpServiceEvent.PromptEvent(event))
                    when (event) {
                        is Event.SessionUpdateEvent -> emitEvent(AcpServiceEvent.SessionUpdateReceived(event.update))
                        is Event.PromptResponseEvent -> emitEvent(AcpServiceEvent.PromptFinished(event.response))
                    }
                    emit(event)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            emitEvent(AcpServiceEvent.PromptFailed(prompt, t))
            throw t
        }
    }

    override suspend fun disconnect(reason: String?) {
        lifecycleMutex.withLock {
            disconnectLocked(reason ?: "Disconnected")
        }
    }

    override fun dispose() {
        disposed = true
        runBlocking {
            lifecycleMutex.withLock {
                disconnectLocked("Agent service disposed")
            }
        }
        serviceScope.cancel()
    }

    private suspend fun disconnectLocked(reason: String) {
        val connection = runtimeConnection ?: return
        runtimeConnection = null

        runCatching {
            connection.close()
        }.onFailure { t ->
            if (t is CancellationException) {
                throw t
            }
            logger.warn("Error while disconnecting ACP agent ${descriptor.displayName}", t)
        }

        emitState(
            AcpConnectionState.Disconnected(
                descriptor = descriptor,
                sessionId = connection.session.sessionId.value,
                reason = reason,
            )
        )
    }

    private fun connectedState(connection: AcpRuntimeConnection): AcpConnectionState.Connected =
        AcpConnectionState.Connected(
            descriptor = descriptor,
            sessionId = connection.session.sessionId.value,
            agentInfo = connection.agentInfo,
        )

    private suspend fun emitState(state: AcpConnectionState) {
        _connectionState.value = state
        _events.emit(AcpServiceEvent.ConnectionStateChanged(state))
    }

    private suspend fun emitEvent(event: AcpServiceEvent) {
        _events.emit(event)
    }

    private fun ensureUsable() {
        check(!disposed) { "ACP agent service ${descriptor.displayName} is already disposed" }
    }
}
