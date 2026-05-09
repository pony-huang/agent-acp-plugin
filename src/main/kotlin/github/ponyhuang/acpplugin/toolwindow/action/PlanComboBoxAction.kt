package github.ponyhuang.acpplugin.toolwindow.action

import com.agentclientprotocol.model.SessionMode
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentListener
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.openapi.project.Project

/**
 * Plan ComboBox - for selecting agent execution plans/permissions
 * @author: pony
 */
class PlanComboBoxAction(
    project: Project? = null,
    private val onPlanSelected: (PlanItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : BaseSelectorComboBoxAction<PlanComboBoxAction.PlanItem>(
    placeholderText = { MyBundle.message("combobox.selectPlan") }
), AgentListener {

    init {
        agentNotifier?.addListener(this)
    }

    fun getSelectedPlan(): PlanItem? = selectedItem

    fun updateModes(modes: List<SessionMode>) {
        replaceItemsPreservingSelection(
            newItems = modes.map { mode ->
                PlanItem(
                    id = mode.id.toString(),
                    displayName = mode.name,
                    description = mode.description ?: ""
                )
            },
            selectFirstWhenMissing = true
        )
    }

    fun setSelectedById(planId: String?) {
        setSelectedByIdOrKeepNull(planId)
    }

    fun clearModes() {
        clearItems()
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

    override fun isSelectorEnabled(): Boolean = items.isNotEmpty()

    override fun onItemSelected(item: PlanItem) {
        onPlanSelected(item)
    }

    data class PlanItem(
        override val id: String,
        override val displayName: String,
        override val description: String
    ) : SelectorComboBoxItem
}
