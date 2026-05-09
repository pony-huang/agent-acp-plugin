package github.ponyhuang.acpplugin.toolwindow.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.Icon
import javax.swing.JComponent

interface SelectorComboBoxItem {
    val id: String
    val displayName: String
    val description: String
    val icon: Icon?
        get() = null
}

abstract class BaseSelectorComboBoxAction<T : SelectorComboBoxItem> internal constructor(
    private val placeholderText: () -> String,
    initialItems: List<T> = emptyList(),
    initialSelection: T? = null,
) : ComboBoxAction(), DumbAware {
    protected var items: List<T> = initialItems
    protected var selectedItem: T? = initialSelection
    private var buttonComponent: ComboBoxButton? = null

    init {
        isSmallVariant = true
    }

    protected fun replaceItemsPreservingSelection(
        newItems: List<T>,
        selectFirstWhenMissing: Boolean
    ): Pair<T?, T?> {
        val previousSelection = selectedItem
        items = newItems
        selectedItem = previousSelection?.let { current ->
            newItems.find { it.id == current.id }
        } ?: if (selectFirstWhenMissing) {
            newItems.firstOrNull()
        } else {
            null
        }
        refreshPresentation()
        return previousSelection to selectedItem
    }

    protected fun setSelectedByIdOrKeepNull(id: String?) {
        selectedItem = items.find { it.id == id } ?: selectedItem?.takeIf { id == null }
        refreshPresentation()
    }

    protected fun clearItems() {
        items = emptyList()
        selectedItem = null
        refreshPresentation()
    }

    protected fun refreshPresentation() {
        val selection = selectedItem
        val text = selection?.displayName ?: placeholderText()
        val icon = selection?.icon
        templatePresentation.text = text
        templatePresentation.icon = icon
        buttonComponent?.let { button ->
            button.text = text
            button.icon = icon
            button.isEnabled = isSelectorEnabled()
            button.repaint()
        }
    }

    protected open fun isSelectorEnabled(): Boolean = true

    protected open fun onItemSelected(item: T) {
    }

    override fun createPopupActionGroup(
        component: JComponent,
        dataContext: DataContext
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            items.forEach { item ->
                add(object : AnAction(item.displayName, item.description, item.icon) {
                    override fun actionPerformed(e: AnActionEvent) {
                        selectedItem = item
                        refreshPresentation()
                        onItemSelected(item)
                    }

                    override fun update(e: AnActionEvent) {
                        super.update(e)
                        e.presentation.isPerformGroup = selectedItem?.id == item.id
                    }
                })
            }
        }
    }

    override fun createCustomComponent(
        presentation: Presentation,
        place: String
    ): JComponent {
        return createComboBoxButton(presentation).apply {
            buttonComponent = this
            text = selectedItem?.displayName ?: placeholderText()
            icon = selectedItem?.icon
            setForeground(EditorColorsManager.getInstance().globalScheme.defaultForeground)
            setBorder(null)
            margin = JBUI.insets(0, 6, 0, 4)
            putClientProperty("ActionToolbar.smallVariant", true)
            putClientProperty("JButton.backgroundColor", Color(0, 0, 0, 0))
            isEnabled = isSelectorEnabled()
        }
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        event.presentation.text = selectedItem?.displayName ?: placeholderText()
        event.presentation.icon = selectedItem?.icon
        event.presentation.isVisible = true
        event.presentation.isEnabled = isSelectorEnabled()
    }
}
