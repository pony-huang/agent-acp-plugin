package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionCoordinator internal constructor(
    private val sessionOperations: SessionLifecycleOperations,
    private val notifier: SessionNotificationSink,
    private val cwdProvider: () -> String = { System.getProperty("user.dir") },
) : Disposable {
    constructor(
        sessionService: AcpSessionService,
        notifier: ToolWindowNotifier,
        cwdProvider: () -> String = { System.getProperty("user.dir") },
    ) : this(
        sessionOperations = AcpSessionLifecycleOperations(sessionService),
        notifier = notifier,
        cwdProvider = cwdProvider
    )

    private val logger = Logger.getInstance(SessionCoordinator::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentAgentId = MutableStateFlow(sessionOperations.currentAgentId())
    val currentAgentId: StateFlow<String?> = _currentAgentId.asStateFlow()

    private val _isDisconnecting = MutableStateFlow(false)
    val isDisconnecting: StateFlow<Boolean> = _isDisconnecting.asStateFlow()

    private val agentSwitchController = AgentSwitchController(
        scope = scope,
        isConnected = { sessionOperations.isConnected.value },
        currentConnectedAgentId = { currentAgentId.value ?: sessionOperations.currentAgentId() },
        connectAgent = { request -> connect(request) },
        onSwitchFailed = { agent, t ->
            logger.warn("Failed to switch ACP session to ${agent.displayName}", t)
            notifier.notifyError(
                groupTitle = MyBundle.message("notification.connectionError"),
                title = MyBundle.message("notification.failedConnect", agent.displayName),
                content = t.message ?: MyBundle.message("notification.unknownError")
            )
        }
    )

    val isSwitching: StateFlow<Boolean> = agentSwitchController.isSwitching

    init {
        scope.launch {
            sessionOperations.isConnected.collect { connected ->
                _currentAgentId.value = if (connected) sessionOperations.currentAgentId() else null
            }
        }
    }

    fun requestSwitch(agent: AgentRegistry.InstalledAgent): String? {
        if (sessionOperations.isLoading.value) {
            logger.info(
                "[ToolWindowState] agentSwitchIgnored: target=${agent.id}, reason=sessionLoading, " +
                    "connected=${sessionOperations.isConnected.value}, switching=${isSwitching.value}, " +
                    "connectedAgentId=${currentAgentId.value ?: "<none>"}"
            )
            return null
        }
        val traceId = agentSwitchController.requestSwitch(agent)
        logger.info(
            "[ToolWindowState] agentSwitchRequested: target=${agent.id}, connected=${sessionOperations.isConnected.value}, " +
                "connectedAgentId=${currentAgentId.value ?: "<none>"}, traceId=${traceId ?: "<none>"}"
        )
        return traceId
    }

    suspend fun connect(agent: AgentRegistry.InstalledAgent, cwd: String) {
        connect(
            AgentSwitchController.SwitchRequest(
                traceId = "direct-connect",
                agent = agent
            ),
            cwd = cwd
        )
    }

    fun createNewSession(agent: AgentRegistry.InstalledAgent?, cwd: String) {
        if (agent == null) {
            notifier.notifyNoAgentSelected()
            return
        }

        scope.launch {
            try {
                logger.info("Creating a new ACP session for agent ${agent.displayName}")
                sessionOperations.createSession(agent, cwd)
                _currentAgentId.value = agent.id
                notifier.notifyNewSessionCreated(agent.displayName)
            } catch (t: Throwable) {
                logger.warn("Failed to create a new ACP session for ${agent.displayName}", t)
                notifier.notifyError(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.failedCreateSession"),
                    content = t.message ?: MyBundle.message("notification.unknownError")
                )
            }
        }
    }

    fun resumeSession(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        session: AcpSessionService.SessionListItem
    ) {
        val sessionId = session.sessionId
        logger.info("[Sessions] Resuming session $sessionId for agent ${agent.displayName}")
        scope.launch {
            try {
                sessionOperations.resumeSession(sessionId, agent, cwd)
                _currentAgentId.value = agent.id
                notifier.notifySessionResumed(session)
            } catch (t: Throwable) {
                logger.warn("Failed to resume ACP session $sessionId", t)
                notifier.notifyError(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.failedResumeSession"),
                    content = t.message ?: MyBundle.message("notification.unknownError")
                )
            }
        }
    }

    suspend fun disconnect(traceId: String = "manual-disconnect") {
        logger.info(
            "[ToolWindowState][$traceId] disconnectCurrentSessionStart: connectedAgentId=${currentAgentId.value ?: "<none>"}, " +
                "serviceCurrentAgentId=${sessionOperations.currentAgentId() ?: "<none>"}, " +
                "serviceConnected=${sessionOperations.isConnected.value}, serviceLoading=${sessionOperations.isLoading.value}"
        )
        _isDisconnecting.value = true
        try {
            _currentAgentId.value = null
            sessionOperations.disconnect()
            notifier.notifyDisconnected()
            logger.info(
                "[ToolWindowState][$traceId] disconnectCurrentSessionDone: connectedAgentId=${currentAgentId.value ?: "<none>"}, " +
                    "serviceCurrentAgentId=${sessionOperations.currentAgentId() ?: "<none>"}, " +
                    "serviceConnected=${sessionOperations.isConnected.value}, serviceLoading=${sessionOperations.isLoading.value}"
            )
        } finally {
            _isDisconnecting.value = false
        }
    }

    suspend fun cancel() {
        sessionOperations.cancel()
    }

    private suspend fun connect(request: AgentSwitchController.SwitchRequest) {
        val cwd = cwdProvider()
        connect(request, cwd)
    }

    private suspend fun connect(request: AgentSwitchController.SwitchRequest, cwd: String) {
        val agent = request.agent
        logger.info(
            "[ToolWindowState][${request.traceId}] connectSelectedAgentStart: agentId=${agent.id}, " +
                "displayName=${agent.displayName}, cwd=$cwd, serviceConnected=${sessionOperations.isConnected.value}, " +
                "serviceLoading=${sessionOperations.isLoading.value}"
        )
        if (sessionOperations.isConnected.value) {
            sessionOperations.replaceSession(agent, cwd)
        } else {
            sessionOperations.createSession(agent, cwd)
        }
        _currentAgentId.value = agent.id
        logger.info(
            "[ToolWindowState][${request.traceId}] connectSelectedAgentDone: agentId=${agent.id}, " +
                "connectedAgentId=${currentAgentId.value ?: "<none>"}, serviceConnected=${sessionOperations.isConnected.value}, " +
                "serviceLoading=${sessionOperations.isLoading.value}"
        )
        notifier.notifyConnected(agent.displayName)
    }

    override fun dispose() {
        scope.cancel()
    }
}

internal interface SessionLifecycleOperations {
    val isConnected: StateFlow<Boolean>
    val isLoading: StateFlow<Boolean>
    fun currentAgentId(): String?
    suspend fun createSession(agent: AgentRegistry.InstalledAgent, cwd: String)
    suspend fun replaceSession(agent: AgentRegistry.InstalledAgent, cwd: String)
    suspend fun resumeSession(sessionId: String, agent: AgentRegistry.InstalledAgent, cwd: String)
    suspend fun disconnect()
    suspend fun cancel()
}

private class AcpSessionLifecycleOperations(
    private val sessionService: AcpSessionService
) : SessionLifecycleOperations {
    override val isConnected: StateFlow<Boolean> = sessionService.isConnected
    override val isLoading: StateFlow<Boolean> = sessionService.isLoading
    override fun currentAgentId(): String? = sessionService.currentAgentId()
    override suspend fun createSession(agent: AgentRegistry.InstalledAgent, cwd: String) {
        sessionService.createSession(agent, cwd)
    }
    override suspend fun replaceSession(agent: AgentRegistry.InstalledAgent, cwd: String) {
        sessionService.replaceSession(agent, cwd)
    }
    override suspend fun resumeSession(sessionId: String, agent: AgentRegistry.InstalledAgent, cwd: String) {
        sessionService.resumeSession(sessionId, agent, cwd)
    }
    override suspend fun disconnect() {
        sessionService.disconnect()
    }
    override suspend fun cancel() {
        sessionService.cancel()
    }
}
