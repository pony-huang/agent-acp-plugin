package github.ponyhuang.acpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.services.AcpAgentIconService
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.action.AgentComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.ui.chat.ChatViewPanel
import github.ponyhuang.acpplugin.toolwindow.ui.composer.ComposerInputPanel
import github.ponyhuang.acpplugin.toolwindow.ui.chat.PlanEntriesPanel
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
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class AcpToolWindowPanel(
    var project: Project,
    var disposable: Disposable
) : SimpleToolWindowPanel(true) {
    companion object {
        private const val DEFAULT_MAIN_SPLITTER_PROPORTION = 0.8f
    }

    private val logger: Logger = Logger.getInstance(AcpToolWindowPanel::class.java)

    private val configService = project.service<github.ponyhuang.acpplugin.services.AcpAgentsConfigService>()
    private val agentIconService = ApplicationManager.getApplication().getService(AcpAgentIconService::class.java)
    private val sessionService = project.service<AcpSessionService>()
    private val notifier = ToolWindowNotifier(project)
    private val sessionCoordinator = SessionCoordinator(
        sessionService = sessionService,
        notifier = notifier,
        cwdProvider = { currentCwd() }
    )
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isListingSessions = MutableStateFlow(false)

    // Create agent selection notifier for linkage
    private val agentNotifier = AgentNotifier()

    private val conversationPanel = ChatViewPanel(project, disposable)

    // Initialize userInputPanel with linkage mechanism (callbacks set in init block)
    private val userInputPanel = ComposerInputPanel(
        project = project,
        agentItems = buildAgentItems(),
        agentNotifier = agentNotifier,
        onConnectionToggle = {},
        onModelChanged = { model -> logger.info("Model changed: ${model.id}") },
        onPlanChanged = { plan -> logger.info("Plan changed: ${plan.id}") }
    ).apply {
        minimumSize = Dimension(0, JBUI.scale(140))
    }
    private val toolbarController = ToolbarController(
        project = project,
        loading = sessionService.isLoading,
        connected = sessionService.isConnected,
        switching = sessionCoordinator.isSwitching,
        listingSessions = isListingSessions,
        isLoading = { isComposerBusy() },
        isListingSessions = { isListingSessions.value },
        hasSelectedAgent = { userInputPanel.selectedAgent() != null },
        onNewSession = { createNewSession() },
        onShowSessions = { showSessionPopup() },
        onCancel = {
            uiScope.launch {
                sessionCoordinator.cancel()
            }
        },
        isSessionConnected = { sessionService.isConnected.value },
        getComposerState = { currentComposerState() }
    )

    private val controller = userInputPanel
    private val planEntriesPanel: PlanEntriesPanel = PlanEntriesPanel().apply {
        isVisible = false
    }
    private val composerContainer: JPanel = JPanel(BorderLayout())
    private val sessionPopupManager = SessionPopupManager(
        anchorComponent = toolbarController.component,
        onResumeSession = { agent, cwd, session -> resumeSession(agent, cwd, session) }
    )
    private val stateBinder = ToolWindowStateBinder(
        sessionService = sessionService,
        switching = sessionCoordinator.isSwitching,
        currentAgentId = sessionCoordinator.currentAgentId,
        configChanges = configService.configChanges,
        userInputPanel = userInputPanel,
        toolbarController = toolbarController,
        planEntriesPanel = planEntriesPanel,
        composerContainer = composerContainer,
        buildAgentItems = { buildAgentItems() }
    )

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
                        sessionCoordinator.cancel()
                    } else if (sessionService.isConnected.value) {
                        sessionCoordinator.disconnect()
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
                    notifier.notifyError(
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
                        "connectedAgentId=${sessionCoordinator.currentAgentId.value ?: "<none>"}"
                )
                requestAgentSwitch(agentItem.agentDefinition)
            }
            toolbarController.update()
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
        Disposer.register(disposable, controller)
        Disposer.register(disposable, toolbarController)
        Disposer.register(disposable, sessionCoordinator)
        Disposer.register(disposable, sessionPopupManager)
        Disposer.register(disposable, stateBinder)
        Disposer.register(disposable) { uiScope.cancel() }
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
        toolbar = toolbarController.component
    }

    internal fun showSessionPopup() {
        logger.info("[Sessions] Show sessions requested")
        val agent = userInputPanel.selectedAgent()
        logger.info("[Sessions] Selected agent at request time: ${agent?.displayName ?: "<none>"}")
        if (agent == null) {
            logger.warn("[Sessions] No agent selected, cannot list sessions")
            notifier.notifyNoAgentSelected()
            return
        }

        val cwd = currentCwd()
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
                notifier.notifyFailedListSessions(t.message ?: MyBundle.message("notification.unknownError"))
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
                    "switching=${sessionCoordinator.isSwitching.value}, connectedAgentId=${sessionCoordinator.currentAgentId.value ?: "<none>"}"
            )
            return
        }
        val traceId = sessionCoordinator.requestSwitch(agent)
        logToolWindowState(
            event = "agentSwitchRequested",
            traceId = traceId,
            details = "target=${agent.id}, connected=${sessionService.isConnected.value}, " +
                "connectedAgentId=${sessionCoordinator.currentAgentId.value ?: "<none>"}"
        )
    }

    private suspend fun connectSelectedAgent(agent: AgentRegistry.InstalledAgent) {
        sessionCoordinator.connect(agent, currentCwd())
    }

    internal fun createNewSession() {
        sessionCoordinator.createNewSession(userInputPanel.selectedAgent(), currentCwd())
    }

    internal fun showSessionPopup(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        sessions: List<AcpSessionService.SessionListItem>
    ) {
        sessionPopupManager.show(agent, cwd, sessions)
    }

    internal fun buildLoadedSessionNotificationContent(
        session: AcpSessionService.SessionListItem
    ): String {
        return notifier.buildLoadedSessionNotificationContent(session)
    }

    internal fun resumeSession(
        agent: AgentRegistry.InstalledAgent,
        cwd: String,
        session: AcpSessionService.SessionListItem
    ) {
        val sessionId = session.sessionId
        logger.info("[Sessions] Resuming session $sessionId for agent ${agent.displayName}")
        sessionCoordinator.resumeSession(agent, cwd, session)
    }

    private fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }

    private fun isComposerBusy(): Boolean {
        return sessionService.isLoading.value || sessionCoordinator.isSwitching.value
    }

    private fun currentComposerState(
        loading: Boolean = sessionService.isLoading.value,
        connected: Boolean = sessionService.isConnected.value,
        switching: Boolean = sessionCoordinator.isSwitching.value
    ): ToolWindowComposerState {
        return deriveToolWindowComposerState(loading = loading, connected = connected, switching = switching)
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

    private fun currentCwd(): String = project.basePath ?: System.getProperty("user.dir")
}
