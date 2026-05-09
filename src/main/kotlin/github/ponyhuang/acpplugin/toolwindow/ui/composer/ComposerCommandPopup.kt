package github.ponyhuang.acpplugin.toolwindow.ui.composer

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

internal class ComposerCommandPopup(
    private val userInputTextArea: JTextArea,
    private val commandController: ComposerCommandController,
    private val isBusy: () -> Boolean
) : Disposable {
    private var filteredCommands: List<ComposerCommandItem> = emptyList()
    private var commandPopup: JBPopup? = null

    private val commandListModel = DefaultListModel<ComposerCommandItem>()
    private val commandList = JBList(commandListModel).apply {
        visibleRowCount = 8
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<ComposerCommandItem>() {
            override fun customizeCellRenderer(
                list: JList<out ComposerCommandItem>,
                value: ComposerCommandItem?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                append("/${value.name}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val suffix = value.description.ifBlank { value.hint.orEmpty() }.trim()
                if (suffix.isNotEmpty()) {
                    append("  $suffix", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        addListSelectionListener { repaint() }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 1) {
                    applySelectedCommand()
                }
            }
        })
    }

    fun shouldShow(text: String): Boolean {
        return commandController.shouldShowSuggestions(text, isBusy())
    }

    fun refresh() {
        val text = userInputTextArea.text
        if (!shouldShow(text)) {
            hide()
            return
        }

        filteredCommands = commandController.filterCommands(text)
        if (filteredCommands.isEmpty()) {
            hide()
            return
        }

        commandListModel.removeAllElements()
        filteredCommands.forEach(commandListModel::addElement)
        if (commandList.selectedIndex !in filteredCommands.indices) {
            commandList.selectedIndex = 0
        }

        if (commandPopup?.isVisible != true) {
            show()
        } else {
            commandList.revalidate()
            commandList.repaint()
        }
    }

    fun scheduleRefresh() {
        SwingUtilities.invokeLater { refresh() }
    }

    fun hide() {
        commandPopup?.cancel()
        commandPopup = null
    }

    fun handleKeyEvent(e: KeyEvent): Boolean {
        if (commandPopup?.isVisible != true || filteredCommands.isEmpty()) {
            return false
        }

        when (e.keyCode) {
            KeyEvent.VK_DOWN -> {
                e.consume()
                val nextIndex = (commandList.selectedIndex + 1).mod(filteredCommands.size)
                commandList.selectedIndex = nextIndex
                commandList.ensureIndexIsVisible(nextIndex)
                return true
            }

            KeyEvent.VK_UP -> {
                e.consume()
                val nextIndex = if (commandList.selectedIndex <= 0) {
                    filteredCommands.lastIndex
                } else {
                    commandList.selectedIndex - 1
                }
                commandList.selectedIndex = nextIndex
                commandList.ensureIndexIsVisible(nextIndex)
                return true
            }

            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                e.consume()
                applySelectedCommand()
                return true
            }

            KeyEvent.VK_ESCAPE -> {
                e.consume()
                hide()
                return true
            }
        }

        return false
    }

    private fun applySelectedCommand() {
        val selected = commandList.selectedValue ?: filteredCommands.firstOrNull() ?: return
        userInputTextArea.text = commandController.commandText(selected)
        userInputTextArea.caretPosition = userInputTextArea.text.length
        hide()
        userInputTextArea.requestFocusInWindow()
    }

    private fun show() {
        hide()

        val popupContent = JBScrollPane(commandList).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(220))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        commandPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, commandList)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .createPopup()

        try {
            val caretBounds = userInputTextArea.modelToView2D(userInputTextArea.caretPosition).bounds
            val point = Point(caretBounds.x, caretBounds.y + caretBounds.height)
            SwingUtilities.convertPointToScreen(point, userInputTextArea)
            commandPopup?.show(RelativePoint(point))
        } catch (_: Exception) {
            commandPopup?.showInBestPositionFor(DataManager.getInstance().getDataContext(userInputTextArea))
        }
    }

    override fun dispose() {
        hide()
    }
}
