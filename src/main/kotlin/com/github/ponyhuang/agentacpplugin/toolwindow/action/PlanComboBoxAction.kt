package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.github.ponyhuang.agentacpplugin.services.AgentSelectionListener
import com.github.ponyhuang.agentacpplugin.services.AgentSelectionNotifier
import com.github.ponyhuang.agentacpplugin.services.BuiltInAcpAgentRegistry
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
    private val agentSelectionNotifier: AgentSelectionNotifier? = null
) : ComboBoxAction(), DumbAware, AgentSelectionListener {

    private val mockPlans = listOf(
        PlanItem("read-only", "Read Only", "Can read files and code only"),
        PlanItem("read-write", "Read/Write", "Can read and modify files"),
        PlanItem("execute", "Execute", "Can execute commands and scripts"),
        PlanItem("full-access", "Full Access", "Full system access with caution")
    )

    private var selectedPlan: PlanItem = mockPlans[1] // Default to read-write

    init {
        agentSelectionNotifier?.addListener(this)
    }

    fun getSelectedPlan(): PlanItem = selectedPlan

    // AgentSelectionListener implementation
    override fun onAgentSelected(agent: BuiltInAcpAgentRegistry.AgentDefinition) {
        // Plans could be filtered based on agent capabilities in the future
    }

    override fun onAgentDeselected() {
        // Reset to default plan if needed
    }

    fun dispose() {
        agentSelectionNotifier?.removeListener(this)
    }

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            mockPlans.forEach { plan ->
                add(object : AnAction(plan.displayName, plan.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedPlan = plan
                        templatePresentation.text = plan.displayName
                        onPlanSelected(plan)
                        if (component is ComboBoxButton) {
                            component.text = plan.displayName
                            component.repaint()
                        }
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isPerformGroup = (selectedPlan.id == plan.id)
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
        button.text = selectedPlan.displayName
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedPlan.displayName
        event.presentation.isVisible = true
    }

    data class PlanItem(
        val id: String,
        val displayName: String,
        val description: String
    )
}
