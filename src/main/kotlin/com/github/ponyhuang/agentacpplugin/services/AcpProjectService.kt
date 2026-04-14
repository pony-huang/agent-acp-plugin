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

internal interface ProjectSessionCoordinator {
    suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): String

    suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress)

    suspend fun cancel(sessionId: String)

    fun disconnect(sessionId: String)
}

@Service(Service.Level.PROJECT)
class AcpProjectService private constructor(
    private val project: Project,
    dependencies: ServiceDependencies,
) : Disposable, SessionUpdateIngress {
    private val scope = dependencies.scope
    private val endpointStore = dependencies.endpointStore
    private val turnStore = dependencies.turnStore
    private val snapshotStore = dependencies.snapshotStore
    private val snapshotPublisher = dependencies.snapshotPublisher
    private val mapper = dependencies.mapper
    private val permissionRequestHandler = dependencies.permissionRequestHandler
    private val sessionCoordinator = dependencies.sessionCoordinator

    constructor(project: Project) : this(project, createDefaultDependencies(project))

    internal constructor(
        project: Project,
        scope: CoroutineScope,
        sessionCoordinator: ProjectSessionCoordinator,
        endpointStore: AgentEndpointStore = AgentEndpointStore(),
        turnStore: ConversationTurnStore = ConversationTurnStore(),
        snapshotStore: SessionViewSnapshotStore = SessionViewSnapshotStore(),
        snapshotPublisher: UiSnapshotPublisher = UiSnapshotPublisher(),
        mapper: SessionUpdateRenderMapper = SessionUpdateRenderMapper(),
        permissionRequestHandler: PermissionRequestHandler = PermissionRequestHandler(),
    ) : this(
        project = project,
        dependencies = ServiceDependencies(
            scope = scope,
            endpointStore = endpointStore,
            turnStore = turnStore,
            snapshotStore = snapshotStore,
            snapshotPublisher = snapshotPublisher,
            mapper = mapper,
            permissionRequestHandler = permissionRequestHandler,
            sessionCoordinator = sessionCoordinator,
        ),
    )

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
                val sessionId = sessionCoordinator.connect(
                    endpointId = endpointId,
                    endpointName = endpointName,
                    commandLine = trimmed,
                    workspaceRoot = workspaceRoot,
                    ingress = this@AcpProjectService,
                    permissionRequestHandler = permissionRequestHandler,
                )
                endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.CONNECTED, capabilitiesSummary = "ACP")
                endpointStore.createSession(endpointId, sessionId, title = endpointName)
                selectedSessionId = sessionId
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

    private companion object {
        private fun createDefaultDependencies(project: Project): ServiceDependencies {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val permissionRequestHandler = PermissionRequestHandler()
            val registry = com.github.ponyhuang.agentacpplugin.services.session.SessionRegistry()
            return ServiceDependencies(
                scope = scope,
                endpointStore = AgentEndpointStore(),
                turnStore = ConversationTurnStore(),
                snapshotStore = SessionViewSnapshotStore(),
                snapshotPublisher = UiSnapshotPublisher(),
                mapper = SessionUpdateRenderMapper(),
                permissionRequestHandler = permissionRequestHandler,
                sessionCoordinator = createProjectSessionCoordinator(scope, registry),
            )
        }

        private fun createProjectSessionCoordinator(
            scope: CoroutineScope,
            registry: com.github.ponyhuang.agentacpplugin.services.session.SessionRegistry,
        ): ProjectSessionCoordinator {
            val delegate = AcpSessionCoordinator(AcpClientFacade(scope, registry))
            return object : ProjectSessionCoordinator {
                override suspend fun connect(
                    endpointId: String,
                    endpointName: String,
                    commandLine: String,
                    workspaceRoot: Path,
                    ingress: SessionUpdateIngress,
                    permissionRequestHandler: PermissionRequestHandler,
                ): String {
                    return delegate.connect(
                        endpointId = endpointId,
                        endpointName = endpointName,
                        commandLine = commandLine,
                        workspaceRoot = workspaceRoot,
                        ingress = ingress,
                        permissionRequestHandler = permissionRequestHandler,
                    ).sessionId.toString()
                }

                override suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
                    delegate.submitPrompt(sessionId, prompt, ingress)
                }

                override suspend fun cancel(sessionId: String) {
                    delegate.cancel(sessionId)
                }

                override fun disconnect(sessionId: String) {
                    delegate.disconnect(sessionId)
                }
            }
        }
    }
}

private data class ServiceDependencies(
    val scope: CoroutineScope,
    val endpointStore: AgentEndpointStore,
    val turnStore: ConversationTurnStore,
    val snapshotStore: SessionViewSnapshotStore,
    val snapshotPublisher: UiSnapshotPublisher,
    val mapper: SessionUpdateRenderMapper,
    val permissionRequestHandler: PermissionRequestHandler,
    val sessionCoordinator: ProjectSessionCoordinator,
)
