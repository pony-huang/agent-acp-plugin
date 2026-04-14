package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.acp.AcpClientFacade
import com.github.ponyhuang.agentacpplugin.services.acp.AcpProtocolDebugLogger
import com.github.ponyhuang.agentacpplugin.services.acp.AcpSessionCoordinator
import com.github.ponyhuang.agentacpplugin.services.acp.PendingPermissionRequest
import com.github.ponyhuang.agentacpplugin.services.acp.PermissionRequestHandler
import com.github.ponyhuang.agentacpplugin.services.acp.SessionUpdateIngress
import com.github.ponyhuang.agentacpplugin.services.render.RenderIntent
import com.github.ponyhuang.agentacpplugin.services.render.SessionUpdateRenderMapper
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewState
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewStateAssembler
import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointStore
import com.github.ponyhuang.agentacpplugin.services.session.ConversationTurnStore
import com.github.ponyhuang.agentacpplugin.services.session.SessionStatus
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

interface ProjectSessionCoordinator {
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
    val project: Project,
    val dependencies: ServiceDependencies,
) : Disposable, SessionUpdateIngress {
    private val logger = Logger.getInstance(AcpProjectService::class.java)
    private val scope = dependencies.scope
    private val endpointStore = dependencies.endpointStore
    private val turnStore = dependencies.turnStore
    private val sessionViewAssembler = dependencies.sessionViewAssembler
    private val mapper = dependencies.mapper
    private val permissionRequestHandler = dependencies.permissionRequestHandler
    private val sessionCoordinator = dependencies.sessionCoordinator
    private val _sessionViews = MutableStateFlow<Map<String, SessionViewState>>(emptyMap())

    constructor(project: Project) : this(project, createDefaultDependencies(project))

    internal constructor(
        project: Project,
        scope: CoroutineScope,
        sessionCoordinator: ProjectSessionCoordinator,
        endpointStore: AgentEndpointStore = AgentEndpointStore(),
        turnStore: ConversationTurnStore = ConversationTurnStore(),
        sessionViewAssembler: SessionViewStateAssembler = SessionViewStateAssembler(),
        mapper: SessionUpdateRenderMapper = SessionUpdateRenderMapper(),
        permissionRequestHandler: PermissionRequestHandler = PermissionRequestHandler(),
    ) : this(
        project = project,
        dependencies = ServiceDependencies(
            scope = scope,
            endpointStore = endpointStore,
            turnStore = turnStore,
            sessionViewAssembler = sessionViewAssembler,
            mapper = mapper,
            permissionRequestHandler = permissionRequestHandler,
            sessionCoordinator = sessionCoordinator,
        ),
    )

    @Volatile
    private var selectedSessionId: String? = null

    val sessionViews: StateFlow<Map<String, SessionViewState>> = _sessionViews.asStateFlow()

    val pendingPermissionRequest: StateFlow<PendingPermissionRequest?> = permissionRequestHandler.pendingRequest

    fun selectedSessionId(): String? = selectedSessionId

    fun connect(commandLine: String) {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val endpointId = "endpoint-${UUID.randomUUID()}"
        val endpointName = trimmed.substringBefore(' ')
        AcpProtocolDebugLogger.logConnectRequested(logger, endpointId, endpointName, trimmed, project.basePath?.let(Paths::get) ?: Path.of("."))
        endpointStore.createEndpoint(endpointId, endpointName)
        endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.CONNECTING)
        updateSessionViews()
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
                AcpProtocolDebugLogger.logConnectSucceeded(logger, endpointId, endpointName, sessionId)
                endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.CONNECTED, capabilitiesSummary = "ACP")
                endpointStore.createSession(endpointId, sessionId, title = endpointName)
                selectedSessionId = sessionId
                updateSessionViews()
            }.onFailure { error ->
                AcpProtocolDebugLogger.logConnectFailed(logger, endpointId, endpointName, error)
                endpointStore.updateEndpoint(endpointId, AgentConnectionStatus.FAILED, errorSummary = error.message ?: "Connection failed")
                updateSessionViews()
            }
        }
    }

    fun submitPrompt(prompt: String) {
        val sessionId = selectedSessionId ?: return
        val text = prompt.trim()
        if (text.isEmpty()) {
            return
        }
        AcpProtocolDebugLogger.logPromptSubmitted(logger, sessionId, text)
        turnStore.startTurn(sessionId, text)
        endpointStore.updateSession(sessionId) { session ->
            session.copy(
                sessionStatus = SessionStatus.STREAMING,
                unreadStreamingIndicator = false,
                bannerMessage = null,
            )
        }
        updateSessionViews()
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
        AcpProtocolDebugLogger.logPromptCancellationRequested(logger, sessionId)
        scope.launch {
            runCatching { sessionCoordinator.cancel(sessionId) }
                .onFailure { onPromptFailed(sessionId, it.message ?: "Failed to cancel prompt") }
        }
    }

    fun selectSession(sessionId: String) {
        selectedSessionId = sessionId
        endpointStore.updateSession(sessionId) { it.copy(unreadStreamingIndicator = false) }
        updateSessionViews()
    }

    fun disconnectSession(sessionId: String) {
        AcpProtocolDebugLogger.logSessionDisconnected(logger, sessionId)
        sessionCoordinator.disconnect(sessionId)
        endpointStore.updateSession(sessionId) {
            it.copy(sessionStatus = SessionStatus.CLOSED, bannerMessage = "Session disconnected")
        }
        updateSessionViews()
    }

    override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
        AcpProtocolDebugLogger.logSessionUpdate(logger, "ingress", sessionId, update)
        val intents = mapper.map(update)
        applyIntents(sessionId, intents)
    }

    override fun onPromptFinished(sessionId: String, reason: TurnCompletionReason) {
        AcpProtocolDebugLogger.logPromptFinished(logger, "ingress", sessionId, reason)
        applyIntents(sessionId, mapper.promptFinished(reason))
    }

    override fun onPromptFailed(sessionId: String, message: String) {
        AcpProtocolDebugLogger.logPromptFailed(logger, "ingress", sessionId, message)
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
        updateSessionViews()
    }

    private fun updateSessionViews() {
        val sessionViews = endpointStore.allSessions().associate { session ->
            val endpoint = requireNotNull(endpointStore.getEndpoint(session.endpointId))
            session.sessionId to sessionViewAssembler.assemble(
                endpoint = endpoint,
                session = session,
                timeline = turnStore.timeline(session.sessionId),
                toolCalls = turnStore.toolCalls(session.sessionId),
            )
        }
        _sessionViews.value = sessionViews
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
                sessionViewAssembler = SessionViewStateAssembler(),
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

data class ServiceDependencies(
    val scope: CoroutineScope,
    val endpointStore: AgentEndpointStore,
    val turnStore: ConversationTurnStore,
    val sessionViewAssembler: SessionViewStateAssembler,
    val mapper: SessionUpdateRenderMapper,
    val permissionRequestHandler: PermissionRequestHandler,
    val sessionCoordinator: ProjectSessionCoordinator,
)
