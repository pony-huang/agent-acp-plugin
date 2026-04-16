package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AcpTrafficService
import com.github.ponyhuang.agentacpplugin.services.TrafficDirection
import com.github.ponyhuang.agentacpplugin.services.TrafficEntry
import com.github.ponyhuang.agentacpplugin.services.TrafficFilter
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.Vector
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.table.JTableHeader

/**
 * Traffic monitoring tool window panel.
 * Displays ACP JSON-RPC traffic logs similar to acp-ui's traffic store.
 */
class AcpTrafficToolWindowPanel(
    private val project: Project,
    private val disposable: Disposable,
) : SimpleToolWindowPanel(true) {

    private val trafficService: AcpTrafficService = project.service<AcpTrafficService>()

    // UI Components
    private val searchField = JTextField(15)
    private val pauseButton = JToggleButton("Pause")
    private val clearButton = JButton("Clear")

    // Column names
    private val columnNames = arrayOf("Time", "Dir", "Type", "Method", "Payload", "Err")

    // Table model for traffic entries
    private val tableModel = TrafficTableModel()
    private val table = JTable(tableModel)

    init {
        val toolbar = createToolbar()
        setToolbar(toolbar)

        val scrollPane = ScrollPaneFactory.createScrollPane(table)
        setContent(scrollPane)

        setupTable()
        refreshTable()
    }

    private fun createToolbar(): JPanel {
        val panel = JPanel(BorderLayout())

        // Filter buttons
        val filterPanel = JPanel(BorderLayout())
        val allBtn = JToggleButton("All").apply { isSelected = true }
        val reqBtn = JToggleButton("Req")
        val resBtn = JToggleButton("Res")
        val notBtn = JToggleButton("Not")

        allBtn.addActionListener { trafficService.setFilter(TrafficFilter.ALL) }
        reqBtn.addActionListener { trafficService.setFilter(TrafficFilter.REQUESTS) }
        resBtn.addActionListener { trafficService.setFilter(TrafficFilter.RESPONSES) }
        notBtn.addActionListener { trafficService.setFilter(TrafficFilter.NOTIFICATIONS) }

        filterPanel.add(allBtn, BorderLayout.WEST)
        filterPanel.add(reqBtn, BorderLayout.CENTER)
        filterPanel.add(resBtn, BorderLayout.EAST)
        filterPanel.add(notBtn, BorderLayout.LINE_END)

        // Search and control buttons
        val controlPanel = JPanel(BorderLayout())
        searchField.addActionListener {
            trafficService.setSearch(searchField.text)
        }
        pauseButton.addActionListener {
            trafficService.togglePause()
            pauseButton.text = if (trafficService.isPaused.value) "Resume" else "Pause"
        }
        clearButton.addActionListener {
            trafficService.clear()
            refreshTable()
        }

        controlPanel.add(searchField, BorderLayout.WEST)
        controlPanel.add(pauseButton, BorderLayout.CENTER)
        controlPanel.add(clearButton, BorderLayout.EAST)

        panel.add(filterPanel, BorderLayout.WEST)
        panel.add(controlPanel, BorderLayout.EAST)

        return panel
    }

    private fun setupTable() {
        // Set column widths
        table.columnModel.getColumn(0).preferredWidth = 90   // Time
        table.columnModel.getColumn(1).preferredWidth = 35  // Dir
        table.columnModel.getColumn(2).preferredWidth = 45  // Type
        table.columnModel.getColumn(3).preferredWidth = 140 // Method
        table.columnModel.getColumn(4).preferredWidth = 350 // Payload
        table.columnModel.getColumn(5).preferredWidth = 30  // Err

        // Direction renderer (color coded)
        table.columnModel.getColumn(1).cellRenderer = DirectionCellRenderer()

        // Error renderer (red highlight)
        table.columnModel.getColumn(5).cellRenderer = ErrorCellRenderer()
    }

    private fun refreshTable() {
        SwingUtilities.invokeLater {
            tableModel.updateEntries(trafficService.entries.value)
        }
    }

    private fun truncate(str: String, maxLen: Int): String {
        return if (str.length > maxLen) str.take(maxLen) + "..." else str
    }

    /**
     * Custom table model for traffic entries.
     */
    private inner class TrafficTableModel : AbstractTableModel() {
        private var entries: List<TrafficEntry> = emptyList()
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

        fun updateEntries(newEntries: List<TrafficEntry>) {
            entries = newEntries
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = entries.size

        override fun getColumnCount(): Int = columnNames.size

        override fun getColumnName(column: Int): String = columnNames[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                0 -> dateFormat.format(entry.timestamp)
                1 -> when (entry.direction) {
                    TrafficDirection.IN -> "IN"
                    TrafficDirection.OUT -> "OUT"
                }
                2 -> entry.type.name.take(3)
                3 -> entry.method
                4 -> truncate(entry.payload, 80)
                5 -> if (entry.error) "!" else ""
                else -> ""
            }
        }
    }

    /**
     * Direction cell renderer with color coding.
     */
    private class DirectionCellRenderer : TableCellRenderer {
        private val renderer = DefaultTableCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?,
            isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): Component {
            val comp = renderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            comp.background = when (value) {
                "IN" -> Color(200, 230, 200)
                "OUT" -> Color(200, 200, 240)
                else -> Color.WHITE
            }
            return comp
        }
    }

    /**
     * Error cell renderer with red highlight.
     */
    private class ErrorCellRenderer : TableCellRenderer {
        private val renderer = DefaultTableCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?,
            isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): Component {
            val comp = renderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (value == "!") {
                comp.background = Color(255, 180, 180)
                comp.foreground = Color.RED
            } else {
                comp.background = Color.WHITE
                comp.foreground = Color.BLACK
            }
            return comp
        }
    }
}

/**
 * Factory for the Traffic Tool Window.
 */
class AcpTrafficToolWindowFactory : com.intellij.openapi.wm.ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: com.intellij.openapi.wm.ToolWindow) {
        val panel = AcpTrafficToolWindowPanel(project, toolWindow.disposable)
        val content = com.intellij.ui.content.ContentFactory.getInstance()
            .createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}