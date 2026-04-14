package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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

    private val _sessionUpdates = MutableSharedFlow<SessionUpdate>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    private var runtimeConnection: AcpRuntimeConnection? = null

    @Volatile
    private var disposed = false

    private val connected = AtomicBoolean(false)

    override val sessionUpdates: SharedFlow<SessionUpdate> = _sessionUpdates.asSharedFlow()
    override val isConnected: Boolean
        get() = connected.get()

    override suspend fun connect() {
        lifecycleMutex.withLock {
            ensureUsable()
            if (connected.get()) return

            runtimeConnector.connect(project, serviceScope, descriptor, ::emitSessionUpdate).let {
                runtimeConnection = it
                connected.set(true)
            }
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
                    when (event) {
                        is Event.SessionUpdateEvent -> emitSessionUpdate(event.update)
                        else -> { /* ignore other event types */ }
                    }
                    emit(event)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
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
        connected.set(false)

        runCatching {
            connection.close()
        }.onFailure { t ->
            if (t is CancellationException) {
                throw t
            }
            logger.warn("Error while disconnecting ACP agent ${descriptor.displayName}", t)
        }
    }

    private suspend fun emitSessionUpdate(update: SessionUpdate) {
        _sessionUpdates.emit(update)
    }

    private fun ensureUsable() {
        check(!disposed) { "ACP agent service ${descriptor.displayName} is already disposed" }
    }
}
