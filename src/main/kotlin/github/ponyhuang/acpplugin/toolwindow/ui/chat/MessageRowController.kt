package github.ponyhuang.acpplugin.toolwindow.ui.chat

import com.intellij.openapi.project.Project

internal class MessageRowController(
    private val project: Project,
    initialModel: MessageRenderModel,
    private val onPermissionSubmit: (String, String) -> Unit,
    private val onCancelPrompt: () -> Unit,
    private val onThoughtToggled: (String, Boolean) -> Unit
) {
    private var model: MessageRenderModel = initialModel
    var component: MessageCardPanel = createComponent(initialModel)
        private set

    fun update(nextModel: MessageRenderModel): Boolean {
        var replaced = false
        if (model.structureKey != nextModel.structureKey || model.message.role != nextModel.message.role) {
            component.dispose()
            component = createComponent(nextModel)
            replaced = true
        } else {
            component.update(nextModel)
        }
        model = nextModel
        return replaced
    }

    fun collectPermissionCards(target: MutableMap<String, PermissionRequestCardPanel>) {
        component.collectPermissionCards(target)
    }

    fun dispose() {
        component.dispose()
    }

    private fun createComponent(model: MessageRenderModel): MessageCardPanel {
        return MessageCardPanel(
            project = project,
            message = model.message,
            onPermissionSubmit = onPermissionSubmit,
            onPermissionCardCreated = { _, _ -> },
            onCancelPrompt = onCancelPrompt,
            promptState = model.promptState,
            thoughtExpanded = model.thoughtExpanded,
            onThoughtToggled = { expanded ->
                onThoughtToggled(model.message.id, expanded)
            }
        )
    }
}
