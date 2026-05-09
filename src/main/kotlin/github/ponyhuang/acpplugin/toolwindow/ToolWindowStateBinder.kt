package github.ponyhuang.acpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AvailableCommandInput
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.action.AgentComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.ui.composer.ComposerCommandItem
import github.ponyhuang.acpplugin.toolwindow.ui.chat.PlanEntriesPanel
import github.ponyhuang.acpplugin.toolwindow.ui.composer.ComposerInputPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ToolWindowStateBinder(
    private val sessionService: AcpSessionService,
    private val switching: StateFlow<Boolean>,
    private val currentAgentId: StateFlow<String?>,
    private val configChanges: Flow<*>,
    private val userInputPanel: ComposerInputPanel,
    private val toolbarController: ToolbarController,
    private val planEntriesPanel: PlanEntriesPanel,
    private val composerContainer: Container,
    private val buildAgentItems: () -> List<AgentComboBoxAction.AgentItem>,
) : Disposable {
    private val logger = Logger.getInstance(ToolWindowStateBinder::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastComposerState = AtomicReference<ToolWindowComposerState?>(null)

    init {
        bind()
    }

    private fun bind() {
        scope.launch {
            configChanges.collectLatest {
                runOnEdt {
                    userInputPanel.updateAgents(buildAgentItems())
                    toolbarController.update()
                }
            }
        }
        scope.launch {
            combine(
                sessionService.isLoading,
                sessionService.isConnected,
                switching
            ) { loading, connected, switching ->
                deriveToolWindowComposerState(loading = loading, connected = connected, switching = switching)
            }.collectLatest { state ->
                runOnEdt {
                    logComposerStateChange(
                        state = state,
                        reason = "combinedFlow",
                        loading = sessionService.isLoading.value,
                        connected = sessionService.isConnected.value,
                        switching = switching.value
                    )
                    userInputPanel.setBusy(state)
                }
            }
        }
        scope.launch {
            sessionService.isConnected.collectLatest { connected ->
                runOnEdt {
                    logToolWindowState(
                        event = "sessionConnectedChanged",
                        details = "connected=$connected, previousConnectedAgentId=${currentAgentId.value ?: "<none>"}, " +
                            "sessionCurrentAgentId=${sessionService.currentAgentId() ?: "<none>"}"
                    )
                    userInputPanel.setSessionConnected(connected)
                    if (!connected) {
                        userInputPanel.clearSessionSelectors()
                    }
                }
            }
        }
        scope.launch {
            combine(sessionService.availableModes, sessionService.currentModeId) { modes, currentModeId ->
                modes to currentModeId
            }.collectLatest { (modes, currentModeId) ->
                runOnEdt {
                    userInputPanel.updateModes(modes, currentModeId.ifBlank { null })
                }
            }
        }
        scope.launch {
            combine(sessionService.availableModels, sessionService.currentModelId) { models, currentModelId ->
                models to currentModelId
            }.collectLatest { (models, currentModelId) ->
                runOnEdt {
                    userInputPanel.updateModels(models, currentModelId.ifBlank { null })
                }
            }
        }
        scope.launch {
            sessionService.availableCommands.collectLatest { commands ->
                runOnEdt {
                    userInputPanel.updateCommands(
                        commands.map { command ->
                            ComposerCommandItem(
                                name = command.name,
                                description = command.description,
                                hint = (command.input as? AvailableCommandInput.Unstructured)?.hint
                            )
                        }
                    )
                }
            }
        }
        scope.launch {
            sessionService.latestUsage.collectLatest { usage ->
                runOnEdt {
                    updateLatestUsage(usage)
                }
            }
        }
        scope.launch {
            sessionService.latestPlanEntries.collectLatest { entries ->
                runOnEdt {
                    updatePlanEntries(entries)
                }
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
        val application = ApplicationManager.getApplication()
        if (application == null || application.isDispatchThread) {
            action()
        } else {
            application.invokeLater(action)
        }
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

    private fun logToolWindowState(event: String, details: String) {
        logger.info("[ToolWindowState] $event: $details")
    }

    override fun dispose() {
        scope.cancel()
    }
}
