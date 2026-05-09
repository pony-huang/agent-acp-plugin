package github.ponyhuang.acpplugin.toolwindow.ui.composer

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.toolwindow.ToolWindowComposerState
import github.ponyhuang.acpplugin.toolwindow.action.AgentComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.action.ModelComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.action.PlanComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.action.SelectorPanel
import github.ponyhuang.acpplugin.toolwindow.ui.CycleAnimatorIcon
import github.ponyhuang.acpplugin.toolwindow.ui.ProcessStepIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent

internal class ComposerSessionControls(
    private val project: Project,
    agentItems: List<AgentComboBoxAction.AgentItem>,
    private val agentNotifier: AgentNotifier?,
    private val onAgentChanged: (AgentComboBoxAction.AgentItem?) -> Unit,
    private val onModelChanged: (ModelComboBoxAction.ModelItem) -> Unit,
    private val onPlanChanged: (PlanComboBoxAction.PlanItem) -> Unit,
    private val onSubmit: () -> Unit,
    private val onConnectionToggle: () -> Unit
) : Disposable {
    private var isSessionConnected = false
    private var isBusy = false
    private var currentState = ToolWindowComposerState.IDLE
    private var connectionAnimationIcon: CycleAnimatorIcon? = null

    val agentComboBoxAction = AgentComboBoxAction(
        availableAgents = agentItems,
        onAgentSelected = {
            onAgentChanged(it)
            updateControlStates()
        },
        agentNotifier = agentNotifier
    )
    private val agentSelectorPanel = SelectorPanel(
        action = agentComboBoxAction,
        presentation = agentComboBoxAction.templatePresentation,
        place = "ComposerInputPanel",
        selectedItem = { agentComboBoxAction.getSelectedAgent() },
        updateItemsHandler = { items -> agentComboBoxAction.updateAgents(items) }
    )
    private val agentComboBox = agentSelectorPanel.component

    private val planComboBoxAction = PlanComboBoxAction(
        project = project,
        onPlanSelected = { onPlanChanged(it) },
        agentNotifier = agentNotifier
    )
    private val planSelectorPanel = SelectorPanel(
        action = planComboBoxAction,
        presentation = planComboBoxAction.templatePresentation,
        place = "ComposerInputPanel",
        selectedItem = { planComboBoxAction.getSelectedPlan() }
    )
    private val planComboBox = planSelectorPanel.component

    private val modelComboBoxAction = ModelComboBoxAction(
        project = project,
        onModelSelected = { onModelChanged(it) },
        agentNotifier = agentNotifier
    )
    private val modelSelectorPanel = SelectorPanel(
        action = modelComboBoxAction,
        presentation = modelComboBoxAction.templatePresentation,
        place = "ComposerInputPanel",
        selectedItem = { modelComboBoxAction.getSelectedModel() }
    )
    private val modelComboBox = modelSelectorPanel.component

    val sendButton = JButton(MyBundle.message("input.send")).apply {
        icon = AllIcons.Actions.Execute
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        isOpaque = false
        addActionListener { onSubmit() }
    }

    val connectionButton = JButton(MyBundle.message("input.connect")).apply {
        icon = AllIcons.Actions.Execute
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        isOpaque = false
        addActionListener { onConnectionToggle() }
    }

    private val selectorRow = NonOpaquePanel(HorizontalLayout(JBUI.scale(4))).apply {
        add(agentComboBox)
        add(planComboBox)
        add(modelComboBox)
    }

    private val sessionControlsRow = NonOpaquePanel(BorderLayout(JBUI.scale(6), 0)).apply {
        add(selectorRow, BorderLayout.WEST)
        add(sendButton, BorderLayout.EAST)
    }.apply {
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }

    val component: JComponent = NonOpaquePanel(BorderLayout()).apply {
        add(sessionControlsRow, BorderLayout.CENTER)
    }.apply {
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }

    init {
        updateControlStates()
    }

    fun setBusy(state: ToolWindowComposerState) {
        val previousState = currentState
        currentState = state
        isBusy = state != ToolWindowComposerState.IDLE
        updateControlStates()

        when (state) {
            ToolWindowComposerState.CONNECTING -> {
                if (previousState != ToolWindowComposerState.CONNECTING) {
                    connectionAnimationIcon = CycleAnimatorIcon(
                        ProcessStepIcons.icons,
                        onFrameChanged = { connectionButton.repaint() }
                    ).apply { start() }
                    connectionButton.icon = connectionAnimationIcon
                }
            }
            else -> {
                connectionAnimationIcon?.stop()
                connectionAnimationIcon = null
                connectionButton.icon = if (isSessionConnected) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            }
        }
    }

    fun setSessionConnected(connected: Boolean) {
        isSessionConnected = connected
        updateControlStates()
    }

    fun updateModes(modes: List<SessionMode>, currentModeId: String?) {
        planComboBoxAction.updateModes(modes)
        planComboBoxAction.setSelectedById(currentModeId)
        planComboBox.isEnabled = isSessionConnected && !isBusy && modes.isNotEmpty()
    }

    @OptIn(UnstableApi::class)
    fun updateModels(models: List<ModelInfo>, currentModelId: String?) {
        modelComboBoxAction.updateModels(models)
        modelComboBoxAction.setSelectedById(currentModelId)
        modelComboBox.isEnabled = isSessionConnected && !isBusy && models.isNotEmpty()
    }

    fun clearSessionSelectors() {
        planComboBoxAction.clearModes()
        modelComboBoxAction.clearModels()
        updateControlStates()
    }

    fun selectedAgent(): AgentRegistry.InstalledAgent? =
        agentComboBoxAction.getSelectedAgent()?.agentDefinition

    fun updateAgents(agentItems: List<AgentComboBoxAction.AgentItem>) {
        val previousAgentId = agentComboBoxAction.getSelectedAgent()?.id
        agentSelectorPanel.updateItems(agentItems)
        if (agentComboBoxAction.getSelectedAgent()?.id != previousAgentId) {
            onAgentChanged(agentComboBoxAction.getSelectedAgent())
        }
        updateControlStates()
        component.repaint()
    }

    private fun updateControlStates() {
        val hasSelectedAgent = agentComboBoxAction.getSelectedAgent() != null
        val selectorState = ComposerSelectorState.from(
            isSessionConnected = isSessionConnected,
            isBusy = isBusy,
            hasSelectedPlan = planComboBoxAction.getSelectedPlan() != null,
            hasSelectedModel = modelComboBoxAction.getSelectedModel() != null
        )
        agentComboBox.isEnabled = selectorState.agentEnabled
        planComboBox.isEnabled = selectorState.planEnabled
        modelComboBox.isEnabled = selectorState.modelEnabled

        val controlPresentation = ComposerControlStatePresenter.present(
            isSessionConnected = isSessionConnected,
            isBusy = isBusy,
            hasSelectedAgent = hasSelectedAgent
        )
        sendButton.isEnabled = controlPresentation.sendEnabled
        connectionButton.isVisible = controlPresentation.connectionVisible
        connectionButton.isEnabled = controlPresentation.connectionEnabled
        connectionButton.text = controlPresentation.connectionText
        connectionButton.icon = controlPresentation.connectionIcon
        connectionButton.toolTipText = controlPresentation.connectionTooltip
    }

    override fun dispose() {
        connectionAnimationIcon?.stop()
        connectionAnimationIcon = null
    }
}
