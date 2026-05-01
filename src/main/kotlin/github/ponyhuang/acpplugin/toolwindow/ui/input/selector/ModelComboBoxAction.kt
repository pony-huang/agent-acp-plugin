package github.ponyhuang.acpplugin.toolwindow.ui.input.selector

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentListener
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.JComponent

/**
 * Model ComboBoxAction
 * @author: pony
 */
@OptIn(UnstableApi::class)
class ModelComboBoxAction(
    private val project: Project? = null,
    private val onModelSelected: (ModelItem) -> Unit = {},
    private val agentNotifier: AgentNotifier? = null
) : ComboBoxAction(), DumbAware, AgentListener {

    private var models: List<ModelItem> = emptyList()
    private var selectedModel: ModelItem? = null
    private var buttonComponent: ComboBoxButton? = null

    init {
        isSmallVariant = true
        agentNotifier?.addListener(this)
    }

    fun getSelectedModel(): ModelItem? = selectedModel

    @OptIn(UnstableApi::class)
    fun updateModels(modelInfos: List<ModelInfo>) {
        models = modelInfos.map { info ->
            ModelItem(
                id = info.modelId.toString(),
                displayName = info.name,
                description = info.description ?: ""
            )
        }
        selectedModel = selectedModel?.let { current ->
            models.find { it.id == current.id }
        } ?: models.firstOrNull()
        refreshPresentation()
    }

    fun setSelectedById(modelId: String?) {
        selectedModel = models.find { it.id == modelId } ?: selectedModel?.takeIf { modelId == null }
        refreshPresentation()
    }

    fun clearModels() {
        models = emptyList()
        selectedModel = null
        refreshPresentation()
    }

    private fun refreshPresentation() {
        val text = selectedModel?.displayName ?: MyBundle.message("combobox.selectModel")
        templatePresentation.text = text
        buttonComponent?.let { button ->
            button.text = text
            button.isEnabled = models.isNotEmpty()
            button.repaint()
        }
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

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: com.intellij.openapi.actionSystem.DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            models.forEach { model ->
                add(object : AnAction(model.displayName, model.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedModel = model
                        onModelSelected(model)
                        refreshPresentation()
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isPerformGroup = (selectedModel?.id == model.id)
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
        button.text = selectedModel?.displayName ?: MyBundle.message("combobox.selectModel")
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.margin = JBUI.insets(0, 6, 0, 4)
        button.putClientProperty("ActionToolbar.smallVariant", true)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        button.isEnabled = models.isNotEmpty()
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedModel?.displayName ?: MyBundle.message("combobox.selectModel")
        event.presentation.isVisible = true
        event.presentation.isEnabled = models.isNotEmpty()
    }

    data class ModelItem(
        val id: String,
        val displayName: String,
        val description: String
    )
}
