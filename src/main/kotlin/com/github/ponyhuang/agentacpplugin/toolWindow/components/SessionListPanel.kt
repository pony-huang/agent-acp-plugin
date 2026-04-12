package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.toolWindow.model.SessionListItemViewModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JPanel

class SessionListPanel(
    private val onSelect: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val listModel = DefaultListModel<SessionListItemViewModel>()
    private val list = JBList(listModel).apply {
        addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedValue?.sessionId?.let(onSelect)
            }
        }
    }

    init {
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    fun render(items: List<SessionListItemViewModel>) {
        listModel.clear()
        items.forEach(listModel::addElement)
        val index = items.indexOfFirst { it.isSelected }
        if (index >= 0) {
            list.selectedIndex = index
        }
    }
}
