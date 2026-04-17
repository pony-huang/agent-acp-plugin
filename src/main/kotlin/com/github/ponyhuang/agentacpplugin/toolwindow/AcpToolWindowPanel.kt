package com.github.ponyhuang.agentacpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import com.github.ponyhuang.agentacpplugin.services.AgentNotifier
import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.github.ponyhuang.agentacpplugin.toolwindow.action.AgentComboBoxAction
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpChatViewPanel
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpUserInputPanel
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.PlanEntriesPanel
import com.agentclientprotocol.model.AvailableCommandInput
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpChatViewToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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

    private val configService = project.service<com.github.ponyhuang.agentacpplugin.services.AcpAgentsConfigService>()
    private val sessionService = project.service<AcpSessionService>()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Create agent selection notifier for linkage
    private val agentNotifier = AgentNotifier()

    // Get available agents from config
    private val availableAgents = AgentRegistry.getAvailableAgents(configService)

    // Create AgentItem list
    private val agentItems = availableAgents.map { agent ->
        AgentComboBoxAction.AgentItem(
            id = agent.id,
            displayName = agent.displayName,
            description = agent.description,
            agentDefinition = agent
        )
    }

    private val conversationPanel = AcpChatViewPanel(project, disposable)
    private val conversationAcpChatViewToolbar = AcpChatViewToolbar(
        isLoading = { sessionService.isLoading.value },
        onCancel = {
            uiScope.launch {
                sessionService.cancel()
            }
        }
    )

    // Initialize userInputPanel with linkage mechanism (callbacks set in init block)
    private val userInputPanel = AcpUserInputPanel(
        project = project,
        agentItems = agentItems,
        agentNotifier = agentNotifier,
        onModelChanged = { model -> logger.info("Model changed: ${model.id}") },
        onPlanChanged = { plan -> logger.info("Plan changed: ${plan.id}") }
    ).apply {
        minimumSize = Dimension(0, JBUI.scale(140))
    }

    private val controller = userInputPanel
    private val planEntriesPanel: PlanEntriesPanel = PlanEntriesPanel().apply {
        isVisible = false
    }
    private val composerContainer: JPanel = JPanel(BorderLayout())

    init {
        logger.info("AcpToolWindowPanel init")
        Disposer.register(disposable, userInputPanel)
        userInputPanel.onSubmit = { prompt ->
            uiScope.launch {
                try {
                    val cwd = project.basePath ?: System.getProperty("user.dir")
                    if (!sessionService.isConnected.value) {
                        val agent = userInputPanel.selectedAgent()
                        if (agent != null) {
                            sessionService.createSession(agent, cwd)
                        }
                    }
                    sessionService.sendPrompt(prompt)
                } catch (t: Throwable) {
                    logger.warn("Failed to submit prompt", t)
                }
            }
        }
        userInputPanel.onAgentChanged = { agentItem ->
            if (agentItem != null) {
                uiScope.launch {
                    try {
                        val cwd = project.basePath ?: System.getProperty("user.dir")
                        logger.info("Initializing ACP session for selected agent: id=${agentItem.id}, displayName=${agentItem.displayName}, cwd=$cwd")
                        sessionService.disconnect()
                        sessionService.createSession(agentItem.agentDefinition, cwd)
                        Notifications.Bus.notify(
                            Notification(
                                "ACP Connection",
                                "Connected to ${agentItem.displayName}",
                                "ACP session established successfully",
                                NotificationType.INFORMATION
                            ),
                            project
                        )
                    } catch (t: Throwable) {
                        logger.warn("Failed to create session", t)
                        Notifications.Bus.notify(
                            Notification(
                                "ACP Connection Error",
                                "Failed to connect to ${agentItem.displayName}",
                                t.message ?: "Unknown error",
                                NotificationType.ERROR
                            ),
                            project
                        )
                    }
                }
            }
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
            sessionService.isLoading.collectLatest { loading ->
                runOnEdt {
                    userInputPanel.setBusy(
                        if (loading) ToolWindowComposerState.SENDING else ToolWindowComposerState.IDLE
                    )
                }
            }
        }
        uiScope.launch {
            sessionService.isConnected.collectLatest { connected ->
                runOnEdt {
                    userInputPanel.setSessionConnected(connected)
                    if (!connected) {
                        userInputPanel.clearSessionSelectors()
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
                            AcpUserInputPanel.SessionCommandItem(
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
                    userInputPanel.updateLatestUsage(usage)
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
            sessionService.isLoading.collectLatest {
                runOnEdt {
                    conversationAcpChatViewToolbar.update()
                }
            }
        }
        Disposer.register(disposable, controller)
        Disposer.register(disposable, conversationAcpChatViewToolbar)
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
        toolbar = conversationAcpChatViewToolbar
    }

    private fun updatePlanEntries(entries: List<AcpSessionService.SessionPlanItem>) {
        planEntriesPanel.updatePlanEntries(entries)
        planEntriesPanel.isVisible = entries.isNotEmpty()
        composerContainer.revalidate()
        composerContainer.repaint()
    }

    private fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
