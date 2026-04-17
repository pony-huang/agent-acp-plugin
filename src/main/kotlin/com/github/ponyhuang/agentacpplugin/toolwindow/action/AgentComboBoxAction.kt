package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.github.ponyhuang.agentacpplugin.services.AgentNotifier
import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import java.awt.Color
import javax.swing.JComponent

/**
 * Agent ComboBox Action
 * @author: pony
 */
class AgentComboBoxAction(
    private val availableAgents: List<AgentItem>,
    private val onAgentSelected: (AgentItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : ComboBoxAction(), DumbAware {
    private val logger = Logger.getInstance(AgentComboBoxAction::class.java)

    private var selectedAgent: AgentItem? = null

    fun getSelectedAgent(): AgentItem? = selectedAgent

    fun hasSelectedAgent(): Boolean = selectedAgent != null

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            availableAgents.forEach { agent ->
                add(object : AnAction(agent.displayName, agent.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        logger.info("Agent selected from combo box: id=${agent.id}, displayName=${agent.displayName}")
                        selectedAgent = agent
                        templatePresentation.text = agent.displayName
                        onAgentSelected(agent)
                        agentNotifier?.notifyAgentSelected(agent.agentDefinition)
                        if (component is ComboBoxButton) {
                            component.text = agent.displayName
                            component.repaint()
                        }
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isPerformGroup = (selectedAgent?.id == agent.id)
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
        button.text = selectedAgent?.displayName ?: "Select Agent"
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedAgent?.displayName ?: "Select Agent"
        event.presentation.isVisible = true
    }

    data class AgentItem(
        val id: String,
        val displayName: String,
        val description: String,
        val agentDefinition: AgentRegistry.AgentDefinition,
    )
}
