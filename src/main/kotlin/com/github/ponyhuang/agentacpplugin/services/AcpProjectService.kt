package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.TestOnly


@Service(Service.Level.PROJECT)
class AcpProjectService(
    val project: Project,
) : Disposable {

    private val logger = Logger.getInstance(AcpProjectService::class.java)
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("AcpProjectService:${project.name}")
    )
    private val lifecycleMutex = Mutex()
    private val promptMutex = Mutex()

    private val _connectionState = MutableStateFlow<AcpConnectionState>(AcpConnectionState.Idle)
    private val _events = MutableSharedFlow<AcpServiceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var runtimeConnector: AcpRuntimeConnector = ProcessAcpRuntimeConnector
    private var runtimeConnection: AcpRuntimeConnection? = null

    @Volatile
    private var disposed = false

    val connectionState: StateFlow<AcpConnectionState> = _connectionState.asStateFlow()
    val events: SharedFlow<AcpServiceEvent> = _events.asSharedFlow()
    val isConnected: Boolean
        get() = connectionState.value is AcpConnectionState.Connected

    suspend fun connect(vararg command: String): AcpConnectionState.Connected = connect(command.toList())

    suspend fun connect(command: List<String>): AcpConnectionState.Connected = lifecycleMutex.withLock {
        ensureUsable()
        require(command.isNotEmpty()) { "ACP command must not be empty" }

        val existing = runtimeConnection
        if (existing != null) {
            if (existing.command == command) {
                return connectedState(existing)
            }
            disconnectLocked("Reconnecting with a new command")
        }

        emitState(AcpConnectionState.Connecting(command))
        return try {
            val connection = runtimeConnector.connect(project, serviceScope, command, ::emitEvent)
            runtimeConnection = connection
            connectedState(connection).also { emitState(it) }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            logger.warn("Failed to connect ACP service for project ${project.name}", t)
            emitState(
                AcpConnectionState.Failed(
                    command = command,
                    message = t.message ?: "Failed to connect ACP service",
                    cause = t,
                )
            )
            throw t
        }
    }

    fun sendPrompt(text: String): Flow<Event> = flow {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "Prompt text must not be empty" }

        val connection = lifecycleMutex.withLock {
            ensureUsable()
            runtimeConnection ?: error("ACP is not connected")
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

    suspend fun disconnect(reason: String? = null) {
        lifecycleMutex.withLock {
            disconnectLocked(reason ?: "Disconnected")
        }
    }

    override fun dispose() {
        disposed = true
        runBlocking {
            lifecycleMutex.withLock {
                disconnectLocked("Project service disposed")
            }
        }
        serviceScope.cancel()
    }

    @TestOnly
    internal fun replaceRuntimeConnectorForTests(connector: AcpRuntimeConnector) {
        runtimeConnector = connector
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
            logger.warn("Error while disconnecting ACP runtime for project ${project.name}", t)
        }

        emitState(
            AcpConnectionState.Disconnected(
                command = connection.command,
                sessionId = connection.session.sessionId.value,
                reason = reason,
            )
        )
    }

    private fun connectedState(connection: AcpRuntimeConnection): AcpConnectionState.Connected =
        AcpConnectionState.Connected(
            command = connection.command,
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
        check(!disposed) { "ACP project service is already disposed" }
    }
}
