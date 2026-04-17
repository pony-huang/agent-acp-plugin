package com.github.ponyhuang.agentacpplugin.settings

import com.github.ponyhuang.agentacpplugin.services.AcpAgentsConfigService
import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Settings UI for ACP Chat plugin.
 * Provides configuration UI for agents and general settings.
 */
class AcpSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var agentTable: JBTable? = null
    private var agentTableModel: AgentTableModel? = null
    private var formPanel: JPanel? = null
    private var nameField: JBTextField? = null
    private var commandField: JBTextField? = null
    private var argsField: JBTextField? = null
    private var envVarsPanel: EnvVarsEditorPanel? = null
    private var editingIndex: Int = -1
    private var isEditing: Boolean = false

    private val settings get() = AcpPluginSettings.getInstance()

    override fun getDisplayName(): String = "ACP Chat"

    override fun createComponent(): JPanel {
        mainPanel = JPanel(BorderLayout())

        val contentPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(4, 8, 4, 8)
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0

        // Agents section
        gbc.gridy = 0
        gbc.gridx = 0
        contentPanel.add(JLabel("Agents"), gbc)

        gbc.gridy = 1
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 0.6
        contentPanel.add(createAgentsTable(), gbc)

        // Form section
        gbc.gridy = 2
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weighty = 0.0
        contentPanel.add(createFormPanel(), gbc)

        // General settings section
        gbc.gridy = 3
        gbc.gridx = 0
        gbc.gridwidth = 2
        contentPanel.add(createGeneralSettingsPanel(), gbc)

        // Storage path section
        gbc.gridy = 4
        gbc.gridx = 0
        gbc.gridwidth = 2
        contentPanel.add(createStoragePathPanel(), gbc)

        mainPanel?.add(contentPanel, BorderLayout.NORTH)
        return mainPanel!!
    }

    private fun createAgentsTable(): JPanel {
        agentTableModel = AgentTableModel(this)
        agentTable = JBTable(agentTableModel).apply {
            emptyText.text = "No agents configured. Add one to get started."
            preferredScrollableViewportSize = Dimension(500, 200)
        }

        val decorator = ToolbarDecorator.createDecorator(agentTable!!)
            .setAddAction { addAgent() }
            .setRemoveAction { removeSelectedAgent() }
            .setEditAction { editSelectedAgent() }

        return decorator.createPanel()
    }

    private fun createFormPanel(): JPanel {
        formPanel = JPanel(GridBagLayout())
        formPanel?.border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            "Agent Configuration"
        )

        val gbc = GridBagConstraints()
        gbc.insets = Insets(4, 8, 4, 8)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Name field
        gbc.gridy = 0
        gbc.gridx = 0
        formPanel?.add(JLabel("Name:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        nameField = JBTextField(20).apply {
            emptyText.text = "Agent Name"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) {}
                override fun removeUpdate(e: DocumentEvent?) {}
                override fun changedUpdate(e: DocumentEvent?) {}
            })
        }
        formPanel?.add(nameField!!, gbc)

        // Command field
        gbc.gridy = 1
        gbc.gridx = 0
        gbc.weightx = 0.0
        formPanel?.add(JLabel("Command:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        commandField = JBTextField(20).apply {
            emptyText.text = "npx"
        }
        formPanel?.add(commandField!!, gbc)

        // Args field
        gbc.gridy = 2
        gbc.gridx = 0
        gbc.weightx = 0.0
        formPanel?.add(JLabel("Arguments:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        argsField = JBTextField(20).apply {
            emptyText.text = "-y @example/agent"
        }
        formPanel?.add(argsField!!, gbc)

        // Env vars
        gbc.gridy = 3
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0
        envVarsPanel = EnvVarsEditorPanel()
        formPanel?.add(envVarsPanel!!, gbc)

        // Buttons
        gbc.gridy = 4
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weighty = 0.0
        val buttonPanel = JPanel()
        buttonPanel.add(JButton("Save").apply {
            addActionListener { saveAgent() }
        })
        buttonPanel.add(JButton("Cancel").apply {
            addActionListener { cancelEdit() }
        })
        formPanel?.add(buttonPanel, gbc)

        return formPanel!!
    }

    private fun createGeneralSettingsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            "General Settings"
        )

        val gbc = GridBagConstraints()
        gbc.insets = Insets(4, 8, 4, 8)
        gbc.fill = GridBagConstraints.HORIZONTAL

        gbc.gridy = 0
        gbc.gridx = 0
        val autoConnectCheckBox = JCheckBox("Auto-connect to last agent on startup", settings.autoConnectEnabled)
        autoConnectCheckBox.addActionListener {
            settings.autoConnectEnabled = autoConnectCheckBox.isSelected
        }
        panel.add(autoConnectCheckBox, gbc)

        gbc.gridy = 1
        gbc.gridx = 0
        val notificationsCheckBox = JCheckBox("Show startup notifications", settings.showStartupNotifications)
        notificationsCheckBox.addActionListener {
            settings.showStartupNotifications = notificationsCheckBox.isSelected
        }
        panel.add(notificationsCheckBox, gbc)

        return panel
    }

    private fun createStoragePathPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            "Sessions Storage"
        )

        val gbc = GridBagConstraints()
        gbc.insets = Insets(4, 8, 4, 8)
        gbc.fill = GridBagConstraints.HORIZONTAL

        gbc.gridy = 0
        gbc.gridx = 0
        panel.add(JLabel("Storage Path:"), gbc)

        gbc.gridy = 1
        gbc.gridx = 0
        gbc.weightx = 1.0
        val pathField = JBTextField(settings.getEffectiveSessionsPath()).apply {
            toolTipText = "Leave empty to use default location"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) {}
                override fun removeUpdate(e: DocumentEvent?) {}
                override fun changedUpdate(e: DocumentEvent?) {}
            })
        }
        panel.add(pathField, gbc)

        gbc.gridy = 2
        gbc.gridx = 0
        panel.add(JLabel("<html><small>Leave empty to use default: ${settings.getEffectiveSessionsPath()}</small></html>"), gbc)

        return panel
    }

    private fun addAgent() {
        cancelEdit()
        isEditing = false
        editingIndex = -1
        clearForm()
        nameField?.isEnabled = true
    }

    private fun editSelectedAgent() {
        val index = agentTable?.selectedRow ?: return
        if (index < 0 || index >= agentTableModel?.rowCount ?: 0) return

        val agent = agentTableModel?.getAgent(index) ?: return
        isEditing = true
        editingIndex = index
        nameField?.text = agent.name
        nameField?.isEnabled = false // Can't change name when editing
        commandField?.text = agent.command
        argsField?.text = agent.args.joinToString(" ")
        envVarsPanel?.setEnvVars(agent.env.toMutableMap())
    }

    private fun removeSelectedAgent() {
        val index = agentTable?.selectedRow ?: return
        if (index < 0 || index >= agentTableModel?.rowCount ?: 0) return

        val agent = agentTableModel?.getAgent(index) ?: return
        if (confirm("Delete agent \"${agent.name}\"?")) {
            settings.removeAgent(agent.name)
            agentTableModel?.removeRow(index)
            syncToConfigService()
        }
    }

    private fun saveAgent() {
        val name = nameField?.text?.trim() ?: ""
        val command = commandField?.text?.trim() ?: ""
        val argsText = argsField?.text?.trim() ?: ""
        val envVars = envVarsPanel?.getEnvVars() ?: emptyMap()

        if (name.isEmpty()) {
            showError("Name is required")
            return
        }
        if (command.isEmpty()) {
            showError("Command is required")
            return
        }

        // Parse args
        val args = parseArgs(argsText)

        // Save to settings
        settings.saveAgent(name, command, args, envVars)

        // Update table
        if (isEditing && editingIndex >= 0) {
            agentTableModel?.updateRow(editingIndex, name, command, args, envVars.toMutableMap())
        } else {
            agentTableModel?.addRow(name, command, args, envVars.toMutableMap())
        }

        // Sync to AcpAgentsConfigService
        syncToConfigService()

        cancelEdit()
    }

    private fun cancelEdit() {
        isEditing = false
        editingIndex = -1
        clearForm()
        nameField?.isEnabled = true
    }

    private fun clearForm() {
        nameField?.text = ""
        commandField?.text = ""
        argsField?.text = ""
        envVarsPanel?.clear()
    }

    private fun parseArgs(argsString: String): List<String> {
        if (argsString.isBlank()) return emptyList()

        val args = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var quoteChar = ' '

        for (char in argsString) {
            when {
                (char == '"' || char == '\'') && !inQuotes -> {
                    inQuotes = true
                    quoteChar = char
                }
                char == quoteChar && inQuotes -> {
                    inQuotes = false
                    quoteChar = ' '
                }
                char == ' ' && !inQuotes -> {
                    if (current.isNotBlank()) {
                        args.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotBlank()) {
            args.add(current.toString())
        }
        return args
    }

    private fun syncToConfigService() {
        // Sync agent settings to AcpAgentsConfigService
        // This allows the runtime to use the updated config
        try {
            val project = ProjectManager.getInstance().defaultProject
            val configService = project.getService(AcpAgentsConfigService::class.java)

            // Build new config JSON
            val agentsJson = settings.agentSettings.joinToString(",\n") { agent ->
                val envJson = agent.env.entries.joinToString(",\n") { (k, v) ->
                    "\"$k\": \"${v.replace("\"", "\\\"")}\""
                }
                """
                |    "${agent.name}": {
                |      "command": "${agent.command.replace("\"", "\\\"")}",
                |      "args": [${agent.args.joinToString(", ") { "\"${it.replace("\"", "\\\"")}\"" }}],
                |      "env": { $envJson }
                |    }
                """.trimMargin()
            }

            val configJson = """{ "agents": {$agentsJson} }"""
            configService.updateConfig(configJson)
        } catch (e: Exception) {
            showError("Failed to sync config: ${e.message}")
        }
    }

    private fun confirm(message: String): Boolean {
        return JOptionPane.showConfirmDialog(
            mainPanel,
            message,
            "Confirm",
            JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(mainPanel, message, "Error", JOptionPane.ERROR_MESSAGE)
    }

    override fun isModified(): Boolean {
        // Check if any settings were modified
        return true
    }

    override fun apply() {
        // Settings are saved immediately, but we can trigger a sync here
        syncToConfigService()
    }

    override fun reset() {
        // Load settings into UI
        agentTableModel?.clear()
        settings.agentSettings.forEach { agent ->
            agentTableModel?.addRow(agent.name, agent.command, agent.args, agent.env.toMutableMap())
        }
        cancelEdit()
    }

    override fun disposeUIResources() {
        mainPanel = null
        agentTable = null
        agentTableModel = null
        formPanel = null
        nameField = null
        commandField = null
        argsField = null
        envVarsPanel = null
    }

    // Table model for agents
    private class AgentTableModel(private val outer: AcpSettingsConfigurable) : AbstractTableModel() {
        private val columns = listOf("Name", "Command", "Arguments", "Environment")
        private val agents = mutableListOf<AgentRow>()

        class AgentRow(
            val name: String,
            val command: String,
            val args: List<String>,
            val env: Map<String, String>
        )

        override fun getRowCount(): Int = agents.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            if (rowIndex < 0 || rowIndex >= agents.size) return ""
            val agent = agents[rowIndex]
            return when (columnIndex) {
                0 -> agent.name
                1 -> agent.command
                2 -> agent.args.joinToString(" ")
                3 -> "${agent.env.size} variables"
                else -> ""
            }
        }

        fun getAgent(rowIndex: Int): AgentRow? {
            return if (rowIndex >= 0 && rowIndex < agents.size) agents[rowIndex] else null
        }

        fun addRow(name: String, command: String, args: List<String>, env: Map<String, String>) {
            agents.add(AgentRow(name, command, args, env))
            fireTableRowsInserted(agents.size - 1, agents.size - 1)
        }

        fun updateRow(rowIndex: Int, name: String, command: String, args: List<String>, env: Map<String, String>) {
            if (rowIndex >= 0 && rowIndex < agents.size) {
                agents[rowIndex] = AgentRow(name, command, args, env)
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
        }

        fun removeRow(rowIndex: Int) {
            if (rowIndex >= 0 && rowIndex < agents.size) {
                agents.removeAt(rowIndex)
                fireTableRowsDeleted(rowIndex, rowIndex)
            }
        }

        fun clear() {
            val size = agents.size
            agents.clear()
            if (size > 0) {
                fireTableRowsDeleted(0, size - 1)
            }
        }
    }
}

/**
 * Panel for editing environment variables.
 */
class EnvVarsEditorPanel : JPanel() {
    private val envVarRows = mutableListOf<EnvVarRow>()
    private val container = JPanel()
    private val scrollPane = JScrollPane(container)

    init {
        layout = BorderLayout()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)

        add(createHeader(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun createHeader(): JPanel {
        val header = JPanel(BorderLayout())
        header.add(JLabel("Environment Variables"), BorderLayout.WEST)
        header.add(JButton("+ Add").apply {
            addActionListener { addEnvVar() }
        }, BorderLayout.EAST)
        return header
    }

    private fun addEnvVar() {
        val keyField = JTextField(15)
        val valueField = JTextField(20)
        val row = EnvVarRow(keyField, valueField)
        envVarRows.add(row)

        val panel = JPanel()
        panel.add(keyField)
        panel.add(JLabel(" = "))
        panel.add(valueField)
        panel.add(JButton("×").apply {
            addActionListener {
                envVarRows.remove(row)
                container.remove(panel)
                revalidate()
                repaint()
            }
        })

        container.add(panel)
        revalidate()
        repaint()
    }

    fun setEnvVars(env: MutableMap<String, String>) {
        clear()
        for ((key, value) in env) {
            addEnvVar()
            val lastRow = envVarRows.lastOrNull() ?: continue
            lastRow.keyField.text = key
            lastRow.valueField.text = value
        }
    }

    fun getEnvVars(): Map<String, String> {
        return envVarRows.mapNotNull { row ->
            val k = row.keyField.text.trim()
            val v = row.valueField.text.trim()
            if (k.isNotEmpty()) k to v else null
        }.toMap()
    }

    fun clear() {
        envVarRows.clear()
        container.removeAll()
        revalidate()
        repaint()
    }

    private data class EnvVarRow(
        val keyField: JTextField,
        val valueField: JTextField
    )
}