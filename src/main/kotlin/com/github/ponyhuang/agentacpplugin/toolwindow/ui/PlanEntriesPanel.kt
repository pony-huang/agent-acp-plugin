package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class PlanEntriesPanel(
    private val onExpandedChanged: (Boolean) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("Latest Plan")
    private val countLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val toggleLink = ActionLink("").apply {
        addActionListener {
            setExpanded(!expanded)
        }
    }
    private val entryListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(8)
    }
    private val scrollPane = JBScrollPane(entryListPanel).apply {
        border = JBUI.Borders.emptyTop(1)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(0, JBUI.scale(160))
        minimumSize = Dimension(0, JBUI.scale(100))
        viewport.isOpaque = false
        isOpaque = false
    }

    private var entries: List<AcpSessionService.SessionPlanItem> = emptyList()
    private var expanded = true

    init {
        isOpaque = true
        background = JBColor(
            ColorUtil.mix(UIUtil.getPanelBackground(), JBColor(0xF2F7ED, 0x243126), 0.88),
            ColorUtil.mix(UIUtil.getPanelBackground(), JBColor(0xF2F7ED, 0x243126), 0.52)
        )
        border = JBUI.Borders.empty(0, 0, 4, 0)

        add(createHeader(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        refreshUi()
    }

    fun updatePlanEntries(entries: List<AcpSessionService.SessionPlanItem>) {
        this.entries = entries
        refreshUi()
    }

    fun setExpanded(expanded: Boolean) {
        if (this.expanded == expanded) {
            refreshUi()
            return
        }
        this.expanded = expanded
        refreshUi()
        onExpandedChanged(expanded)
    }

    fun isExpanded(): Boolean = expanded

    fun hasEntries(): Boolean = entries.isNotEmpty()

    private fun createHeader(): JComponent {
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(titleLabel)
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(countLabel)
                },
                BorderLayout.WEST
            )
            add(toggleLink, BorderLayout.EAST)
        }
    }

    private fun refreshUi() {
        countLabel.text = if (entries.isEmpty()) "" else "${entries.size} items"
        toggleLink.text = if (expanded) "Hide" else "Show"
        scrollPane.isVisible = expanded && entries.isNotEmpty()
        rebuildEntryRows()
        revalidate()
        repaint()
    }

    private fun rebuildEntryRows() {
        entryListPanel.removeAll()
        entries.forEachIndexed { index, entry ->
            entryListPanel.add(PlanEntryRow(entry))
            if (index != entries.lastIndex) {
                entryListPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
        }
    }
}

private class PlanEntryRow(entry: AcpSessionService.SessionPlanItem) : JBPanel<PlanEntryRow>(BorderLayout()) {
    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)

        add(
            JBLabel(entry.content).apply {
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(4)
            },
            BorderLayout.CENTER
        )
        add(
            JBLabel("${entry.status.replace('_', ' ')} | ${entry.priority}").apply {
                foreground = UIUtil.getContextHelpForeground()
            },
            BorderLayout.SOUTH
        )
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = LEFT_ALIGNMENT
    }
}
