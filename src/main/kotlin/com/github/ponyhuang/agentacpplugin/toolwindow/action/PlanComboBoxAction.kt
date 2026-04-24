package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.agentclientprotocol.model.SessionMode
import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.services.AgentListener
import com.github.ponyhuang.agentacpplugin.services.AgentNotifier
import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Color
import javax.swing.JComponent

/**
 * Plan ComboBox - for selecting agent execution plans/permissions
 * @author: pony
 */
class PlanComboBoxAction(
    private val project: Project? = null,
    private val onPlanSelected: (PlanItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : ComboBoxAction(), DumbAware, AgentListener {

    private var plans: List<PlanItem> = emptyList()
    private var selectedPlan: PlanItem? = null
    private var buttonComponent: ComboBoxButton? = null

    init {
        agentNotifier?.addListener(this)
    }

    fun getSelectedPlan(): PlanItem? = selectedPlan

    fun updateModes(modes: List<SessionMode>) {
        plans = modes.map { mode ->
            PlanItem(
                id = mode.id.toString(),
                displayName = mode.name,
                description = mode.description ?: ""
            )
        }
        selectedPlan = selectedPlan?.let { current ->
            plans.find { it.id == current.id }
        } ?: plans.firstOrNull()
        refreshPresentation()
    }

    fun setSelectedById(planId: String?) {
        selectedPlan = plans.find { it.id == planId } ?: selectedPlan?.takeIf { planId == null }
        refreshPresentation()
    }

    fun clearModes() {
        plans = emptyList()
        selectedPlan = null
        refreshPresentation()
    }

    private fun refreshPresentation() {
        val text = selectedPlan?.displayName ?: MyBundle.message("combobox.selectPlan")
        templatePresentation.text = text
        buttonComponent?.let { button ->
            button.text = text
            button.isEnabled = plans.isNotEmpty()
            button.repaint()
        }
    }

    // AgentListener implementation
    override fun onAgentSelected(agent: AgentRegistry.InstalledAgent) {
        // Plans could be filtered based on agent capabilities in the future
    }

    override fun onAgentDeselected() {
        // Reset to default plan if needed
    }

    fun dispose() {
        agentNotifier?.removeListener(this)
    }

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            plans.forEach { plan ->
                add(object : AnAction(plan.displayName, plan.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedPlan = plan
                        onPlanSelected(plan)
                        refreshPresentation()
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isPerformGroup = (selectedPlan?.id == plan.id)
                    }
                })
            }
        }
    }

    override fun createCustomComponent(
        presentation: com.intellij.openapi.actionSystem.Presentation,
        place: String
    ): JComponent {
        val button = createComboBoxButton(presentation)
        buttonComponent = button
        button.text = selectedPlan?.displayName ?: MyBundle.message("combobox.selectPlan")
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        button.isEnabled = plans.isNotEmpty()
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedPlan?.displayName ?: MyBundle.message("combobox.selectPlan")
        event.presentation.isVisible = true
        event.presentation.isEnabled = plans.isNotEmpty()
    }

    data class PlanItem(
        val id: String,
        val displayName: String,
        val description: String
    )
}
