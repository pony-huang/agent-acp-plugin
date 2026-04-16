package com.github.ponyhuang.agentacpplugin.toolwindow.action

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
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

    init {
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
        if (models.isNotEmpty() && selectedModel == null) {
            selectedModel = models.first()
        }
    }

    // AgentListener implementation
    override fun onAgentSelected(agent: AgentRegistry.AgentDefinition) {
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
        val modelList = if (models.isEmpty()) {
            // Fallback to hardcoded models if no real models available
            listOf(
                ModelItem("claude-opus-4-5", "claude-opus-4-5", "Claude Opus 4.5 - Most capable model"),
                ModelItem("claude-sonnet-4-7", "claude-sonnet-4-7", "Claude Sonnet 4.7 - Balanced performance"),
                ModelItem("claude-haiku-4", "claude-haiku-4", "Claude Haiku 4 - Fast and efficient"),
                ModelItem("claude-3-5-sonnet", "claude-3-5-sonnet", "Claude 3.5 Sonnet - Legacy model")
            )
        } else {
            models
        }

        return DefaultActionGroup().apply {
            modelList.forEach { model ->
                add(object : AnAction(model.displayName, model.description, null) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedModel = model
                        templatePresentation.text = model.displayName
                        onModelSelected(model)
                        if (component is ComboBoxButton) {
                            component.text = model.displayName
                            component.repaint()
                        }
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
        button.text = selectedModel?.displayName ?: "Select Model"
        button.setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
        button.setBorder(null)
        button.putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
        return button
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedModel?.displayName ?: "Select Model"
        event.presentation.isVisible = true
    }

    data class ModelItem(
        val id: String,
        val displayName: String,
        val description: String
    )
}
