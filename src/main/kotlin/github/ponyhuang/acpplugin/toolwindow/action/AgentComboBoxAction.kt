package github.ponyhuang.acpplugin.toolwindow.action

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.openapi.diagnostic.Logger
import javax.swing.Icon

/**
 * Agent ComboBox Action
 * @author: pony
 */
class AgentComboBoxAction(
    availableAgents: List<AgentItem>,
    private val onAgentSelected: (AgentItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : BaseSelectorComboBoxAction<AgentComboBoxAction.AgentItem>(
    placeholderText = { MyBundle.message("combobox.selectAgent") },
    initialItems = availableAgents,
) {
    private val logger = Logger.getInstance(AgentComboBoxAction::class.java)

    fun getSelectedAgent(): AgentItem? = selectedItem

    fun hasSelectedAgent(): Boolean = selectedItem != null

    fun updateAgents(newAgents: List<AgentItem>) {
        val (previousSelection, currentSelection) = replaceItemsPreservingSelection(
            newItems = newAgents,
            selectFirstWhenMissing = false
        )
        if (previousSelection != null && currentSelection == null) {
            agentNotifier?.notifyAgentDeselected()
        }
    }

    override fun onItemSelected(item: AgentItem) {
        logger.info("Agent selected from combo box: id=${item.id}, displayName=${item.displayName}")
        onAgentSelected(item)
        agentNotifier?.notifyAgentSelected(item.agentDefinition)
    }

    data class AgentItem(
        override val id: String,
        override val displayName: String,
        override val description: String,
        override val icon: Icon,
        val agentDefinition: AgentRegistry.InstalledAgent,
    ) : SelectorComboBoxItem
}
