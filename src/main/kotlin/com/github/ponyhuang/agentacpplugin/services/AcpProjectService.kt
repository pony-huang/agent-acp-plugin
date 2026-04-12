package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.acp.AcpClientFacade
import com.github.ponyhuang.agentacpplugin.services.acp.AcpSessionCoordinator
import com.github.ponyhuang.agentacpplugin.services.acp.PermissionRequestHandler
import com.github.ponyhuang.agentacpplugin.services.acp.SessionUpdateIngress
import com.github.ponyhuang.agentacpplugin.services.render.RenderIntent
import com.github.ponyhuang.agentacpplugin.services.render.SessionUpdateRenderMapper
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshotStore
import com.github.ponyhuang.agentacpplugin.services.render.UiSnapshotPublisher
import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointStore
import com.github.ponyhuang.agentacpplugin.services.session.ConversationTurnStore
import com.github.ponyhuang.agentacpplugin.services.session.SessionRegistry
import com.github.ponyhuang.agentacpplugin.services.session.SessionStatus
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Service(Service.Level.PROJECT)
class AcpProjectService(
    private val project: Project,
) : Disposable, SessionUpdateIngress {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpointStore = AgentEndpointStore()
    private val turnStore = ConversationTurnStore()
    private val snapshotStore = SessionViewSnapshotStore()
    private val snapshotPublisher = UiSnapshotPublisher()
    private val mapper = SessionUpdateRenderMapper()
    private val registry = SessionRegistry()
    private val permissionRequestHandler = PermissionRequestHandler()
    private val sessionCoordinator = AcpSessionCoordinator(AcpClientFacade(scope, project, registry))

    @Volatile
    private var selectedSessionId: String? = null

    fun addSnapshotListener(listener: (Map<String, SessionViewSnapshot>) -> Unit): () -> Unit =
        snapshotPublisher.addListener(listener)

    fun addPermissionListener(listener: (com.github.ponyhuang.agentacpplugin.services.acp.PendingPermissionRequest?) -> Unit): () -> Unit =
        permissionRequestHandler.addListener(listener)

    fun snapshots(): Map<String, SessionViewSnapshot> = snapshotPublisher.latest()

    fun selectedSessionId(): String? = selectedSessionId

    fun connect(commandLine: String) {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val endpointId = "endpoint-${UUID.randomUUID()}"
        val endpointName = trimmed.substringBefore(' ')
        endpointStore.createEndpoint(endpointId, endpointName)
        endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.CONNECTING)
        publishSnapshots()
        scope.launch {
            runCatching {
                val workspaceRoot = project.basePath?.let(Paths::get) ?: Path.of(".")
                val registered = sessionCoordinator.connect(
                    endpointId = endpointId,
                    endpointName = endpointName,
                    commandLine = trimmed,
                    workspaceRoot = workspaceRoot,
                    ingress = this@AcpProjectService,
                    permissionRequestHandler = permissionRequestHandler,
                )
                val sessionKey = registered.sessionId.toString()
                endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.CONNECTED, capabilitiesSummary = "ACP")
                endpointStore.createSession(endpointId, sessionKey, title = endpointName)
                selectedSessionId = sessionKey
                publishSnapshots()
            }.onFailure { error ->
                endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.FAILED, errorSummary = error.message ?: "Connection failed")
                publishSnapshots()
            }
        }
    }

    fun submitPrompt(prompt: String) {
        val sessionId = selectedSessionId ?: return
        val text = prompt.trim()
        if (text.isEmpty()) {
            return
        }
        turnStore.startTurn(sessionId, text)
        endpointStore.updateSession(sessionId) { session ->
            session.copy(
                sessionStatus = SessionStatus.STREAMING,
                unreadStreamingIndicator = false,
                bannerMessage = null,
            )
        }
        publishSnapshots()
        scope.launch {
            runCatching {
                sessionCoordinator.submitPrompt(sessionId, text, this@AcpProjectService)
            }.onFailure { error ->
                onPromptFailed(sessionId, error.message ?: "Prompt failed")
            }
        }
    }

    fun cancelSelectedPrompt() {
        val sessionId = selectedSessionId ?: return
        scope.launch {
            runCatching { sessionCoordinator.cancel(sessionId) }
                .onFailure { onPromptFailed(sessionId, it.message ?: "Failed to cancel prompt") }
        }
    }

    fun selectSession(sessionId: String) {
        selectedSessionId = sessionId
        endpointStore.updateSession(sessionId) { it.copy(unreadStreamingIndicator = false) }
        publishSnapshots()
    }

    fun disconnectSession(sessionId: String) {
        sessionCoordinator.disconnect(sessionId)
        endpointStore.updateSession(sessionId) {
            it.copy(sessionStatus = SessionStatus.CLOSED, bannerMessage = "Session disconnected")
        }
        publishSnapshots()
    }

    override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
        val intents = mapper.map(update)
        applyIntents(sessionId, intents)
    }

    override fun onPromptFinished(sessionId: String, reason: TurnCompletionReason) {
        applyIntents(sessionId, mapper.promptFinished(reason))
    }

    override fun onPromptFailed(sessionId: String, message: String) {
        applyIntents(sessionId, mapper.promptFailed(message))
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun applyIntents(sessionId: String, intents: List<RenderIntent>) {
        turnStore.apply(sessionId, intents)
        endpointStore.updateSession(sessionId) { session ->
            intents.fold(session) { current, intent ->
                when (intent) {
                    is RenderIntent.MarkTurnStreaming -> current.copy(
                        sessionStatus = SessionStatus.STREAMING,
                        unreadStreamingIndicator = sessionId != selectedSessionId,
                    )

                    is RenderIntent.MarkTurnCompleted -> current.copy(
                        sessionStatus = SessionStatus.IDLE,
                        bannerMessage = null,
                        unreadStreamingIndicator = sessionId != selectedSessionId,
                    )

                    is RenderIntent.MarkTurnFailed -> current.copy(
                        sessionStatus = SessionStatus.DEGRADED,
                        bannerMessage = intent.message,
                        unreadStreamingIndicator = true,
                    )

                    is RenderIntent.SetBanner -> current.copy(
                        bannerMessage = intent.message,
                        sessionStatus = if (intent.message == null) current.sessionStatus else SessionStatus.DEGRADED,
                    )

                    is RenderIntent.UpdateSessionTitle -> current.copy(title = intent.title ?: current.title)
                    is RenderIntent.UpdateCurrentMode -> current.copy(currentModeId = intent.modeId)
                    is RenderIntent.UpdateUsage -> current.copy(usageSummary = intent.usageSummary)
                    is RenderIntent.UpdateAvailableCommands -> current.copy(availableCommands = intent.commands)
                    is RenderIntent.UpdateConfigOptions -> current.copy(configOptions = intent.options)
                    is RenderIntent.AppendTimeline,
                    is RenderIntent.UpsertToolCall,
                    -> current
                }
            }
        }
        publishSnapshots()
    }

    private fun publishSnapshots() {
        val snapshots = endpointStore.allSessions().associate { session ->
            val endpoint = requireNotNull(endpointStore.getEndpoint(session.endpointId))
            session.sessionId to snapshotStore.rebuild(
                endpoint = endpoint,
                session = session,
                timeline = turnStore.timeline(session.sessionId),
                toolCalls = turnStore.toolCalls(session.sessionId),
            )
        }
        snapshotPublisher.publish(snapshots)
    }
}
