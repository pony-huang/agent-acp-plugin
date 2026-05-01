package github.ponyhuang.acpplugin.toolwindow.ui.input.selector

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Agent ComboBox Action
 * @author: pony
 */
class AgentComboBoxAction(
    availableAgents: List<AgentItem>,
    private val onAgentSelected: (AgentItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : ComboBoxAction(), DumbAware {
    private val logger = Logger.getInstance(AgentComboBoxAction::class.java)

    private var selectedAgent: AgentItem? = null
    private var availableAgents: List<AgentItem> = availableAgents

    init {
        isSmallVariant = true
    }

    fun getSelectedAgent(): AgentItem? = selectedAgent

    fun hasSelectedAgent(): Boolean = selectedAgent != null

    fun updateAgents(newAgents: List<AgentItem>) {
        availableAgents = newAgents
        selectedAgent = selectedAgent?.let { current ->
            newAgents.find { it.id == current.id }
        }
        templatePresentation.text = selectedAgent?.displayName ?: MyBundle.message("combobox.selectAgent")
        templatePresentation.icon = selectedAgent?.icon
        if (selectedAgent == null) {
            agentNotifier?.notifyAgentDeselected()
        }
    }

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            availableAgents.forEach { agent ->
                add(object : AnAction(agent.displayName, agent.description, agent.icon) {
                    override fun actionPerformed(e: AnActionEvent) {
                        logger.info("Agent selected from combo box: id=${agent.id}, displayName=${agent.displayName}")
                        selectedAgent = agent
                        this@AgentComboBoxAction.templatePresentation.text = agent.displayName
                        this@AgentComboBoxAction.templatePresentation.icon = agent.icon
                        onAgentSelected(agent)
                        agentNotifier?.notifyAgentSelected(agent.agentDefinition)
                        if (component is ComboBoxButton) {
                            component.text = agent.displayName
                            component.icon = agent.icon
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
        button.text = selectedAgent?.displayName ?: MyBundle.message("combobox.selectAgent")
        button.icon = selectedAgent?.icon
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.margin = JBUI.insets(0, 6, 0, 4)
        button.putClientProperty("ActionToolbar.smallVariant", true)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedAgent?.displayName ?: MyBundle.message("combobox.selectAgent")
        event.presentation.icon = selectedAgent?.icon
        event.presentation.isVisible = true
    }

    data class AgentItem(
        val id: String,
        val displayName: String,
        val description: String,
        val icon: Icon,
        val agentDefinition: AgentRegistry.InstalledAgent,
    )
}
