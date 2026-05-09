package github.ponyhuang.acpplugin.toolwindow.action

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentListener
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.openapi.project.Project

/**
 * Model ComboBoxAction
 * @author: pony
 */
@OptIn(UnstableApi::class)
class ModelComboBoxAction(
    project: Project? = null,
    private val onModelSelected: (ModelItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : BaseSelectorComboBoxAction<ModelComboBoxAction.ModelItem>(
    placeholderText = { MyBundle.message("combobox.selectModel") }
), AgentListener {

    init {
        agentNotifier?.addListener(this)
    }

    fun getSelectedModel(): ModelItem? = selectedItem

    @OptIn(UnstableApi::class)
    fun updateModels(modelInfos: List<ModelInfo>) {
        replaceItemsPreservingSelection(
            newItems = modelInfos.map { info ->
                ModelItem(
                    id = info.modelId.toString(),
                    displayName = info.name,
                    description = info.description ?: ""
                )
            },
            selectFirstWhenMissing = true
        )
    }

    fun setSelectedById(modelId: String?) {
        setSelectedByIdOrKeepNull(modelId)
    }

    fun clearModels() {
        clearItems()
    }

    // AgentListener implementation
    override fun onAgentSelected(agent: AgentRegistry.InstalledAgent) {
        // Models are UNSTABLE - currently using mock data
        // In the future, could filter models based on agent capabilities
    }

    override fun onAgentDeselected() {
        // Reset to default model if needed
    }

    fun dispose() {
        agentNotifier?.removeListener(this)
    }

    override fun isSelectorEnabled(): Boolean = items.isNotEmpty()

    override fun onItemSelected(item: ModelItem) {
        onModelSelected(item)
    }

    data class ModelItem(
        override val id: String,
        override val displayName: String,
        override val description: String
    ) : SelectorComboBoxItem
}
