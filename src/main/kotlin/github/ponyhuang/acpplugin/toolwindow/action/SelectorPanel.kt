package github.ponyhuang.acpplugin.toolwindow.action

import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Dimension
import javax.swing.JComponent

class SelectorPanel<T>(
    action: ComboBoxAction,
    presentation: Presentation,
    place: String,
    private val selectedItem: () -> T?,
    private val updateItemsHandler: (List<T>) -> Unit = {},
    private val setSelectedHandler: (T?) -> Unit = {},
) {
    val component: JComponent = action.createCustomComponent(presentation, place).apply {
        isOpaque = false
        border = null
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        minimumSize = Dimension(100, 24)
    }

    fun getSelected(): T? = selectedItem()

    fun updateItems(items: List<T>) {
        updateItemsHandler(items)
    }

    fun setSelected(item: T?) {
        setSelectedHandler(item)
    }
}
