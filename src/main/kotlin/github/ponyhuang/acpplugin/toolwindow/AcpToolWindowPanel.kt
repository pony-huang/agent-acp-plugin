package github.ponyhuang.acpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.services.AcpAgentIconService
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.session.SessionPopupPresenter
import github.ponyhuang.acpplugin.toolwindow.support.ToolWindowNotifier
import github.ponyhuang.acpplugin.toolwindow.ui.chat.ChatViewPanel
import github.ponyhuang.acpplugin.toolwindow.ui.input.UserInputPanel
import github.ponyhuang.acpplugin.toolwindow.ui.input.selector.AgentComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.ui.plan.PlanEntriesPanel
import com.agentclientprotocol.model.AvailableCommandInput
import github.ponyhuang.acpplugin.toolwindow.ui.toolbar.ChatViewToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
class AcpToolWindowPanel(
    var project: Project,
    var disposable: Disposable
) : SimpleToolWindowPanel(true) {
    companion object {
        private const val DEFAULT_MAIN_SPLITTER_PROPORTION = 0.8f
        private val SESSION_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    private val logger: Logger = Logger.getInstance(AcpToolWindowPanel::class.java)

    private val configService = project.service<github.ponyhuang.acpplugin.services.AcpAgentsConfigService>()
    private val agentIconService = ApplicationManager.getApplication().getService(AcpAgentIconService::class.java)
    private val sessionService = project.service<AcpSessionService>()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isListingSessions = MutableStateFlow(false)
    private val notifier = ToolWindowNotifier(project)

    // Create agent selection notifier for linkage
    private val agentNotifier = AgentNotifier()

    private val conversationPanel = ChatViewPanel(project, disposable)

    // Initialize userInputPanel with linkage mechanism (callbacks set in init block)
    private val userInputPanel = UserInputPanel(
        project = project,
        agentItems = buildAgentItems(),
        agentNotifier = agentNotifier,
        onConnectionToggle = {},
        onModelChanged = { model -> logger.info("Model changed: ${model.id}") },
        onPlanChanged = { plan -> logger.info("Plan changed: ${plan.id}") }
    ).apply {
        minimumSize = Dimension(0, JBUI.scale(140))
    }
    private val conversationChatViewToolbar = ChatViewToolbar(
        project = project,
        isLoading = { isComposerBusy() },
        isListingSessions = { isListingSessions.value },
        hasSelectedAgent = { userInputPanel.selectedAgent() != null },
        onNewSession = { createNewSession() },
        onShowSessions = { showSessionPopup() },
        onCancel = {
            uiScope.launch {
                sessionService.cancel()
            }
        },
        isSessionConnected = { sessionService.isConnected.value },
        getComposerState = { currentComposerState() }
    )

    private val controller = userInputPanel
    private var connectedAgentId: String? = null
    private val agentSwitchController = AgentSwitchController(
        scope = uiScope,
        isConnected = { sessionService.isConnected.value },
        currentConnectedAgentId = { connectedAgentId ?: sessionService.currentAgentId() },
        connectAgent = { request -> connectSelectedAgent(request) },
        onSwitchFailed = { agent, t ->
            logger.warn("Failed to switch ACP session to ${agent.displayName}", t)
            notifier.error(
                groupTitle = MyBundle.message("notification.connectionError"),
                title = MyBundle.message("notification.failedConnect", agent.displayName),
                content = t.message ?: MyBundle.message("notification.unknownError")
            )
        }
    )
    private val planEntriesPanel: PlanEntriesPanel = PlanEntriesPanel().apply {
        isVisible = false
    }
    private val composerContainer: JPanel = JPanel(BorderLayout())
    private val sessionPopupPresenter = SessionPopupPresenter(
        anchorComponent = { conversationChatViewToolbar.component },
        buildSubtitle = ::buildSessionSubtitle
    )
    private val lastComposerState = AtomicReference<ToolWindowComposerState?>(null)

    init {
        Disposer.register(disposable, userInputPanel)
        userInputPanel.onSubmit = { prompt ->
            uiScope.launch {
                try {
                    sessionService.sendPrompt(prompt)
                } catch (t: Throwable) {
                    logger.warn("Failed to submit prompt", t)
                }
            }
        }
        userInputPanel.onConnectionToggle = {
            uiScope.launch {
                try {
                    if (sessionService.isConnected.value && sessionService.isLoading.value) {
                        sessionService.cancel()
                    } else if (sessionService.isConnected.value) {
                        disconnectCurrentSession()
                        notifier.info(
                            groupTitle = MyBundle.message("notification.acpConnection"),
                            title = MyBundle.message("notification.disconnected"),
                            content = MyBundle.message("notification.sessionDisconnected")
                        )
                    } else {
                        val agent = userInputPanel.selectedAgent() ?: return@launch
                        connectSelectedAgent(agent)
                    }
                } catch (t: Throwable) {
                    logger.warn("Failed to toggle ACP session", t)
                    val selectedAgentName = userInputPanel.selectedAgent()?.displayName
                    val title = if (sessionService.isConnected.value && sessionService.isLoading.value) {
                        MyBundle.message("notification.failedInterrupt")
                    } else if (sessionService.isConnected.value) {
                        MyBundle.message("notification.failedDisconnect")
                    } else {
                        MyBundle.message("notification.failedConnect", selectedAgentName ?: MyBundle.message("combobox.selectAgent"))
                    }
                    notifier.error(
                        groupTitle = MyBundle.message("notification.connectionError"),
                        title = title,
                        content = t.message ?: MyBundle.message("notification.unknownError")
                    )
                }
            }
        }
        userInputPanel.onAgentChanged = { agentItem ->
            if (agentItem != null) {
                logToolWindowState(
                    event = "agentSelectionChanged",
                    details = "selectedId=${agentItem.id}, selectedName=${agentItem.displayName}, " +
                        "serviceLoading=${sessionService.isLoading.value}, serviceConnected=${sessionService.isConnected.value}, " +
                        "connectedAgentId=${connectedAgentId ?: "<none>"}"
                )
                requestAgentSwitch(agentItem.agentDefinition)
            }
            conversationChatViewToolbar.update()
        }
        userInputPanel.onPlanChanged = { plan ->
            uiScope.launch {
                try {
                    sessionService.setMode(plan.id)
                } catch (t: Throwable) {
                    logger.warn("Failed to change mode", t)
                }
            }
        }
        userInputPanel.onModelChanged = { model ->
            uiScope.launch {
                try {
                    sessionService.setModel(model.id)
                } catch (t: Throwable) {
                    logger.warn("Failed to change model", t)
                }
            }
        }
        userInputPanel.setBusy(ToolWindowComposerState.IDLE)
        userInputPanel.setSessionConnected(false)
        uiScope.launch {
            configService.configChanges.collectLatest {
                runOnEdt {
                    userInputPanel.updateAgents(buildAgentItems())
                    conversationChatViewToolbar.update()
                }
            }
        }
        uiScope.launch {
            combine(
                sessionService.isLoading,
                sessionService.isConnected,
                agentSwitchController.isSwitching
            ) { loading, connected, switching ->
                currentComposerState(loading = loading, connected = connected, switching = switching)
            }.collectLatest { state ->
                runOnEdt {
                    logComposerStateChange(
                        state = state,
                        reason = "combinedFlow",
                        loading = sessionService.isLoading.value,
                        connected = sessionService.isConnected.value,
                        switching = agentSwitchController.isSwitching.value
                    )
                    userInputPanel.setBusy(state)
                }
            }
        }
        uiScope.launch {
            sessionService.isConnected.collectLatest { connected ->
                runOnEdt {
                    logToolWindowState(
                        event = "sessionConnectedChanged",
                        details = "connected=$connected, previousConnectedAgentId=${connectedAgentId ?: "<none>"}, " +
                            "sessionCurrentAgentId=${sessionService.currentAgentId() ?: "<none>"}"
                    )
                    userInputPanel.setSessionConnected(connected)
                    if (!connected) {
                        connectedAgentId = null
                        userInputPanel.clearSessionSelectors()
                    } else {
                        connectedAgentId = sessionService.currentAgentId()
                    }
                }
            }
        }
        uiScope.launch {
            combine(sessionService.availableModes, sessionService.currentModeId) { modes, currentModeId ->
                modes to currentModeId
            }.collectLatest { (modes, currentModeId) ->
                runOnEdt {
                    userInputPanel.updateModes(modes, currentModeId.ifBlank { null })
                }
            }
        }
        uiScope.launch {
            combine(sessionService.availableModels, sessionService.currentModelId) { models, currentModelId ->
                models to currentModelId
            }.collectLatest { (models, currentModelId) ->
                runOnEdt {
                    userInputPanel.updateModels(models, currentModelId.ifBlank { null })
                }
            }
        }
        uiScope.launch {
            sessionService.availableCommands.collectLatest { commands ->
                runOnEdt {
                    userInputPanel.updateCommands(
                        commands.map { command ->
                            UserInputPanel.SessionCommandItem(
                                name = command.name,
                                description = command.description,
                                hint = (command.input as? AvailableCommandInput.Unstructured)?.hint
                            )
                        }
                    )
                }
            }
        }
        uiScope.launch {
            sessionService.latestUsage.collectLatest { usage ->
                runOnEdt {
                    updateLatestUsage(usage)
                }
            }
        }
        uiScope.launch {
            sessionService.latestPlanEntries.collectLatest { entries ->
                runOnEdt {
                    updatePlanEntries(entries)
                }
            }
        }
        uiScope.launch {
            combine(
                sessionService.isLoading,
                sessionService.isConnected,
                agentSwitchController.isSwitching
            ) { _, _, _ -> currentComposerState() }.collectLatest {
                runOnEdt {
                    conversationChatViewToolbar.update()
                }
            }
        }
        uiScope.launch {
            isListingSessions.collectLatest {
                runOnEdt {
                    conversationChatViewToolbar.update()
                }
            }
        }
        Disposer.register(disposable, controller)
        Disposer.register(disposable, conversationChatViewToolbar)
        Disposer.register(disposable) {
            sessionPopupPresenter.cancel()
            uiScope.cancel()
        }
        composerContainer.isOpaque = false
        composerContainer.add(planEntriesPanel, BorderLayout.NORTH)
        composerContainer.add(userInputPanel, BorderLayout.CENTER)

        val splitter = Splitter(
            true,   // vertical split
            DEFAULT_MAIN_SPLITTER_PROPORTION
        ).apply {
            setFirstComponent(conversationPanel)
            setSecondComponent(composerContainer)
        }
        splitter.setHonorComponentsMinimumSize(true)
        setContent(splitter)
        toolbar = conversationChatViewToolbar.component
    }

    internal fun showSessionPopup() {
        logger.info("[Sessions] Show sessions requested")
        val agent = userInputPanel.selectedAgent()
        logger.info("[Sessions] Selected agent at request time: ${agent?.displayName ?: "<none>"}")
        if (agent == null) {
            logger.warn("[Sessions] No agent selected, cannot list sessions")
            notifier.warning(
                groupTitle = MyBundle.message("notification.acpSessions"),
                title = MyBundle.message("notification.noAgentSelected"),
                content = MyBundle.message("notification.selectAgentBeforeSession")
            )
            return
        }

        val cwd = project.basePath ?: System.getProperty("user.dir")
        isListingSessions.value = true
        logger.info("[Sessions] Launching listSessions coroutine: scopeActive=${uiScope.coroutineContext[kotlinx.coroutines.Job]?.isActive}, cwd=$cwd")
        val job = uiScope.launch {
            logger.info("[Sessions] listSessions coroutine started: isActive=${kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive}")
            try {
                val sessions = sessionService.listSessions(agent, cwd)
                logger.info("[Sessions] listSessions returned to tool window coroutine with ${sessions.size} sessions")
                logger.info("[Sessions] Listed ${sessions.size} sessions for agent ${agent.displayName}")
                runOnEdt {
                    logger.info("[Sessions] Scheduling popup rendering on EDT for ${sessions.size} sessions")
                    showSessionPopup(agent, cwd, sessions)
                }
            } catch (t: CancellationException) {
                logger.warn("[Sessions] listSessions coroutine cancelled after launch", t)
                throw t
            } catch (t: Throwable) {
                logger.warn("Failed to list ACP sessions", t)
                notifier.error(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.failedListSessions"),
                    content = t.message ?: MyBundle.message("notification.unknownError")
                )
            } finally {
                isListingSessions.value = false
            }
        }
        job.invokeOnCompletion { cause ->
            logger.info("[Sessions] listSessions coroutine completed: cancelled=${job.isCancelled}, completed=${job.isCompleted}, cause=${cause?.javaClass?.simpleName ?: "<none>"}${cause?.message?.let { ", message=$it" } ?: ""}")
        }
    }

    private fun requestAgentSwitch(agent: AgentRegistry.InstalledAgent) {
        if (sessionService.isLoading.value) {
            logToolWindowState(
                event = "agentSwitchIgnored",
                details = "target=${agent.id}, reason=sessionLoading, connected=${sessionService.isConnected.value}, " +
                    "switching=${agentSwitchController.isSwitching.value}, connectedAgentId=${connectedAgentId ?: "<none>"}"
            )
            return
        }
        val traceId = agentSwitchController.requestSwitch(agent)
        logToolWindowState(
            event = "agentSwitchRequested",
            traceId = traceId,
            details = "target=${agent.id}, connected=${sessionService.isConnected.value}, " +
                "connectedAgentId=${connectedAgentId ?: "<none>"}"
        )
    }

    private suspend fun connectSelectedAgent(agent: AgentRegistry.InstalledAgent) {
        connectSelectedAgent(AgentSwitchController.SwitchRequest(traceId = "direct-connect", agent = agent))
    }

    private suspend fun connectSelectedAgent(request: AgentSwitchController.SwitchRequest) {
        val agent = request.agent
        val cwd = project.basePath ?: System.getProperty("user.dir")
        logToolWindowState(
            event = "connectSelectedAgentStart",
            traceId = request.traceId,
            details = "agentId=${agent.id}, displayName=${agent.displayName}, cwd=$cwd, " +
                "serviceConnected=${sessionService.isConnected.value}, serviceLoading=${sessionService.isLoading.value}"
        )
        if (sessionService.isConnected.value) {
            sessionService.replaceSession(agent, cwd)
        } else {
            sessionService.createSession(agent, cwd)
        }
        connectedAgentId = agent.id
        logToolWindowState(
            event = "connectSelectedAgentDone",
            traceId = request.traceId,
            details = "agentId=${agent.id}, connectedAgentId=${connectedAgentId ?: "<none>"}, " +
                "serviceConnected=${sessionService.isConnected.value}, serviceLoading=${sessionService.isLoading.value}"
        )
        notifier.info(
            groupTitle = MyBundle.message("notification.acpConnection"),
            title = MyBundle.message("notification.connectedTo", agent.displayName),
            content = MyBundle.message("notification.sessionEstablished")
        )
    }

    internal fun createNewSession() {
        val agent = userInputPanel.selectedAgent()
        if (agent == null) {
            notifier.warning(
                groupTitle = MyBundle.message("notification.acpSessions"),
                title = MyBundle.message("notification.noAgentSelected"),
                content = MyBundle.message("notification.selectAgentBeforeSession")
            )
            return
        }

        val cwd = project.basePath ?: System.getProperty("user.dir")
        uiScope.launch {
            try {
                logger.info("Creating a new ACP session for agent ${agent.displayName}")
                sessionService.createSession(agent, cwd)
                connectedAgentId = agent.id
                notifier.info(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.newSessionCreated"),
                    content = MyBundle.message("notification.sessionCreatedFor", agent.displayName)
                )
            } catch (t: Throwable) {
                logger.warn("Failed to create a new ACP session for ${agent.displayName}", t)
                notifier.error(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.failedCreateSession"),
                    content = t.message ?: MyBundle.message("notification.unknownError")
                )
            }
        }
    }

    internal fun showSessionPopup(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        sessions: List<AcpSessionService.SessionListItem>
    ) {
        logger.info("[Sessions] Creating popup UI with ${sessions.size} sessions")
        sessionPopupPresenter.show(sessions) { session ->
            resumeSession(agent, cwd, session)
        }
    }

    internal fun buildLoadedSessionNotificationContent(
        session: AcpSessionService.SessionListItem
    ): String {
        val title = escapeNotificationText(session.title?.takeIf { it.isNotBlank() } ?: MyBundle.message("session.newSessionTitle"))
        val sessionId = escapeNotificationText(session.sessionId)
        return "<html>$title (sessionId: $sessionId)</html>"
    }

    internal fun resumeSession(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        session: AcpSessionService.SessionListItem
    ) {
        val sessionId = session.sessionId
        logger.info("[Sessions] Resuming session $sessionId for agent ${agent.displayName}")
        uiScope.launch {
            try {
                sessionService.resumeSession(sessionId, agent, cwd)
                connectedAgentId = agent.id
                notifier.info(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.sessionResumed"),
                    content = MyBundle.message("notification.loadedSessionDetails", buildLoadedSessionNotificationContent(session))
                )
            } catch (t: Throwable) {
                logger.warn("Failed to resume ACP session $sessionId", t)
                notifier.error(
                    groupTitle = MyBundle.message("notification.acpSessions"),
                    title = MyBundle.message("notification.failedResumeSession"),
                    content = t.message ?: MyBundle.message("notification.unknownError")
                )
            }
        }
    }

    private fun updatePlanEntries(entries: List<AcpSessionService.SessionPlanItem>) {
        planEntriesPanel.updatePlanEntries(entries)
        composerContainer.revalidate()
        composerContainer.repaint()
    }

    private fun updateLatestUsage(usage: AcpSessionService.SessionUsageSummary?) {
        planEntriesPanel.updateLatestUsage(usage)
        composerContainer.revalidate()
        composerContainer.repaint()
    }

    private fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    private fun isComposerBusy(): Boolean {
        return sessionService.isLoading.value || agentSwitchController.isSwitching.value
    }

    private fun currentComposerState(
        loading: Boolean = sessionService.isLoading.value,
        connected: Boolean = sessionService.isConnected.value,
        switching: Boolean = agentSwitchController.isSwitching.value
    ): ToolWindowComposerState {
        return when {
            switching -> ToolWindowComposerState.CONNECTING
            loading && !connected -> ToolWindowComposerState.CONNECTING
            loading -> ToolWindowComposerState.SENDING
            else -> ToolWindowComposerState.IDLE
        }
    }

    private suspend fun disconnectCurrentSession() {
        disconnectCurrentSession("manual-disconnect")
    }

    private suspend fun disconnectCurrentSession(traceId: String) {
        logToolWindowState(
            event = "disconnectCurrentSessionStart",
            traceId = traceId,
            details = "connectedAgentId=${connectedAgentId ?: "<none>"}, serviceCurrentAgentId=${sessionService.currentAgentId() ?: "<none>"}, " +
                "serviceConnected=${sessionService.isConnected.value}, serviceLoading=${sessionService.isLoading.value}"
        )
        connectedAgentId = null
        sessionService.disconnect()
        logToolWindowState(
            event = "disconnectCurrentSessionDone",
            traceId = traceId,
            details = "connectedAgentId=${connectedAgentId ?: "<none>"}, serviceCurrentAgentId=${sessionService.currentAgentId() ?: "<none>"}, " +
                "serviceConnected=${sessionService.isConnected.value}, serviceLoading=${sessionService.isLoading.value}"
        )
    }

    private fun logComposerStateChange(
        state: ToolWindowComposerState,
        reason: String,
        loading: Boolean,
        connected: Boolean,
        switching: Boolean
    ) {
        val previous = lastComposerState.getAndSet(state)
        if (previous == state) {
            return
        }
        logToolWindowState(
            event = "composerStateChanged",
            details = "reason=$reason, previous=${previous ?: "<none>"}, current=$state, " +
                "loading=$loading, connected=$connected, switching=$switching"
        )
    }

    private fun logToolWindowState(
        event: String,
        traceId: String? = null,
        details: String
    ) {
        val prefix = if (traceId != null) "[ToolWindowState][$traceId]" else "[ToolWindowState]"
        logger.info("$prefix $event: $details")
    }

    private fun buildAgentItems(): List<AgentComboBoxAction.AgentItem> {
        return AgentRegistry.getAvailableAgents(configService).map { agent ->
            AgentComboBoxAction.AgentItem(
                id = agent.id,
                displayName = agent.displayName,
                description = buildAgentDescription(agent),
                icon = agentIconService.loadIcon(agent.iconPath),
                agentDefinition = agent
            )
        }
    }

    private fun buildAgentDescription(agent: AgentRegistry.InstalledAgent): String {
        val parts = buildList {
            if (agent.description.isNotBlank()) {
                add(agent.description)
            }
            if (agent.version.isNotBlank()) {
                add("v${agent.version}")
            }
            add(agent.installMethod.name.lowercase())
            if (agent.sourceLabel.isNotBlank()) {
                add(agent.sourceLabel)
            }
        }
        return parts.joinToString(" • ")
    }

    private fun buildSessionSubtitle(session: AcpSessionService.SessionListItem): String {
        val updatedAt = session.updatedAtMillis?.let {
            SESSION_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(it))
        } ?: MyBundle.message("session.unknownUpdateTime")
        return "$updatedAt • ${shortSessionId(session.sessionId)}"
    }

    private fun shortSessionId(sessionId: String): String {
        return if (sessionId.length <= 10) sessionId else "${sessionId.take(8)}..."
    }

    private fun escapeNotificationText(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
