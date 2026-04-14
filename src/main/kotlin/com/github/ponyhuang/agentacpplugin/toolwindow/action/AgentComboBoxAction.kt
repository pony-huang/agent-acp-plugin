package com.github.ponyhuang.agentacpplugin.toolwindow.action

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
 * Agent ComboBox Action
 * @author: pony
 */
class AgentComboBoxAction(
    private val project: Project? = null,
    private val onAgentSelected: (AgentItem) -> Unit = {}
) : ComboBoxAction(), DumbAware {

    private val mockAgents = listOf(
        AgentItem("code-agent", "Code Agent", "Claude Code for code generation and editing"),
        AgentItem("review-agent", "Review Agent", "Code review and analysis agent"),
        AgentItem("debug-agent", "Debug Agent", "Debugging and issue resolution agent"),
        AgentItem("doc-agent", "Doc Agent", "Documentation generation agent")
    )

    private var selectedAgent: AgentItem = mockAgents.first()

    fun getSelectedAgent(): AgentItem = selectedAgent

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            mockAgents.forEach { agent ->
                add(object : AnAction(agent.displayName, agent.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedAgent = agent
                        templatePresentation.text = agent.displayName
                        onAgentSelected(agent)
                        if (component is ComboBoxButton) {
                            component.text = agent.displayName
                            component.repaint()
                        }
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isPerformGroup = (selectedAgent.id == agent.id)
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
        button.text = selectedAgent.displayName
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedAgent.displayName
        event.presentation.isVisible = true
    }

    data class AgentItem(
        val id: String,
        val displayName: String,
        val description: String
    )
}
