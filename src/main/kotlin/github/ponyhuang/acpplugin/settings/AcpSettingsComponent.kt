package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpAgentRegistryService
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField

class AcpSettingsComponent(
    private val onRefreshRegistry: () -> Unit,
    private val onInstallAgent: (
        AcpAgentRegistryService.RegistryAgent,
        AcpPluginSettings.InstalledAgentSetting?,
        Boolean
    ) -> Unit,
    private val onUninstallAgent: (AcpPluginSettings.InstalledAgentSetting) -> Unit,
    private val onOpenLink: (String) -> Unit,
) {
    companion object {
        private val REFRESH_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    data class GeneralSettingsState(
        val autoConnectEnabled: Boolean,
        val showStartupNotifications: Boolean,
        val sessionsStoragePath: String,
    )

    private val agentListModel = DefaultListModel<AgentRow>()
    private val agentList = JBList(agentListModel)
    private val registryStatusLabel = JBLabel()
    private val installButton = JButton(MyBundle.message("settings.install"))
    private val upgradeButton = JButton(MyBundle.message("settings.upgrade"))
    private val uninstallButton = JButton(MyBundle.message("settings.uninstall"))
    private val openLinkButton = JButton(MyBundle.message("settings.openLink"))
    private val storageCommentLabel = JBLabel()

    private var autoConnectEnabled: Boolean = false
    private var showStartupNotifications: Boolean = true
    private var sessionsStoragePath: String = ""
    private var effectiveSessionsPath: String = ""
    private var storagePathField: JTextField? = null
    private var registrySnapshot: AcpAgentRegistryService.RegistrySnapshot? = null

    private val panel = panel {
        group(MyBundle.message("settings.general")) {
            row {
                checkBox(MyBundle.message("settings.autoConnect"))
                    .bindSelected(::autoConnectEnabled)
            }
            row {
                checkBox(MyBundle.message("settings.showNotifications"))
                    .bindSelected(::showStartupNotifications)
            }
        }
        group(MyBundle.message("settings.sessionsStorage")) {
            row(MyBundle.message("settings.storagePath")) {
                storagePathField = textField()
                    .align(Align.FILL)
                    .resizableColumn()
                    .bindText(::sessionsStoragePath)
                    .component
            }
            row {
                cell(storageCommentLabel)
            }
        }
        group(MyBundle.message("settings.registry")) {
            row {
                cell(registryStatusLabel)
                    .align(Align.FILL)
                    .resizableColumn()
                button(MyBundle.message("settings.refresh")) {
                    onRefreshRegistry()
                }
            }
            row {
                cell(createAgentRegistryPanel())
                    .align(Align.FILL)
                    .resizableColumn()
            }.resizableRow()
        }
    }

    init {
        initializeAgentList()
    }

    fun getPanel(): JComponent = panel

    fun getPreferredFocusedComponent(): JComponent? = storagePathField

    fun getGeneralSettings(): GeneralSettingsState {
        return GeneralSettingsState(
            autoConnectEnabled = autoConnectEnabled,
            showStartupNotifications = showStartupNotifications,
            sessionsStoragePath = sessionsStoragePath,
        )
    }

    fun isGeneralSettingsModified(
        autoConnectEnabled: Boolean,
        showStartupNotifications: Boolean,
        sessionsStoragePath: String,
    ): Boolean {
        return this.autoConnectEnabled != autoConnectEnabled ||
            this.showStartupNotifications != showStartupNotifications ||
            this.sessionsStoragePath != sessionsStoragePath
    }

    fun setGeneralSettings(
        autoConnectEnabled: Boolean,
        showStartupNotifications: Boolean,
        sessionsStoragePath: String,
        effectiveSessionsPath: String,
    ) {
        this.autoConnectEnabled = autoConnectEnabled
        this.showStartupNotifications = showStartupNotifications
        this.sessionsStoragePath = sessionsStoragePath
        this.effectiveSessionsPath = effectiveSessionsPath
        storageCommentLabel.text = MyBundle.message("settings.storageComment", effectiveSessionsPath)
        storageCommentLabel.toolTipText = effectiveSessionsPath
        panel.reset()
    }

    fun setRegistryData(
        snapshot: AcpAgentRegistryService.RegistrySnapshot?,
        installedAgents: List<AcpPluginSettings.InstalledAgentSetting>,
    ) {
        registrySnapshot = snapshot
        refreshAgentRows(installedAgents)
    }

    private fun initializeAgentList() {
        agentList.cellRenderer = object : ColoredListCellRenderer<AgentRow>() {
            override fun customizeCellRenderer(
                list: JList<out AgentRow>,
                value: AgentRow?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) {
                    return
                }
                append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (value.status.isNotBlank()) {
                    append("  ${value.status}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                value.detail.takeIf { it.isNotBlank() }?.let {
                    append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        agentList.addListSelectionListener { updateActionButtons() }

        installButton.addActionListener {
            val selected = agentList.selectedValue as? AgentRow.Registry ?: return@addActionListener
            onInstallAgent(selected.agent, selected.installed, false)
        }
        upgradeButton.addActionListener {
            val selected = agentList.selectedValue as? AgentRow.Registry ?: return@addActionListener
            onInstallAgent(selected.agent, selected.installed, true)
        }
        uninstallButton.addActionListener {
            when (val selected = agentList.selectedValue) {
                is AgentRow.Registry -> selected.installed?.let(onUninstallAgent)
                is AgentRow.Legacy -> onUninstallAgent(selected.installed)
                null -> Unit
            }
        }
        openLinkButton.addActionListener {
            val selected = agentList.selectedValue as? AgentRow.Registry ?: return@addActionListener
            val url = selected.agent.website ?: selected.agent.repository ?: return@addActionListener
            onOpenLink(url)
        }
    }

    private fun createAgentRegistryPanel(): JPanel {
        val actionsPanel = JPanel().apply {
            add(installButton)
            add(upgradeButton)
            add(uninstallButton)
            add(openLinkButton)
        }
        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(agentList), BorderLayout.CENTER)
            add(actionsPanel, BorderLayout.SOUTH)
        }
    }

    private fun refreshAgentRows(installedAgents: List<AcpPluginSettings.InstalledAgentSetting>) {
        val selectedId = (agentList.selectedValue as? AgentRow.Registry)?.agent?.id
            ?: (agentList.selectedValue as? AgentRow.Legacy)?.installed?.displayName
        agentListModel.removeAllElements()

        val installedByRegistryId = installedAgents
            .filter { it.registryAgentId.isNotBlank() && !it.isLegacy }
            .associateBy { it.registryAgentId }

        registrySnapshot?.agents.orEmpty().forEach { agent ->
            agentListModel.addElement(
                AgentRow.Registry(
                    agent = agent,
                    installed = installedByRegistryId[agent.id]
                )
            )
        }

        installedAgents
            .filter { it.isLegacy || it.registryAgentId.isBlank() }
            .sortedBy { it.displayName.lowercase() }
            .forEach { legacy ->
                agentListModel.addElement(AgentRow.Legacy(legacy))
            }

        for (index in 0 until agentListModel.size()) {
            val row = agentListModel.getElementAt(index)
            val rowId = when (row) {
                is AgentRow.Registry -> row.agent.id
                is AgentRow.Legacy -> row.installed.displayName
            }
            if (rowId == selectedId) {
                agentList.selectedIndex = index
                break
            }
        }

        updateRegistryStatus(installedAgents)
        updateActionButtons()
    }

    private fun updateRegistryStatus(installedAgents: List<AcpPluginSettings.InstalledAgentSetting>) {
        val count = registrySnapshot?.agents?.size ?: 0
        val refreshedAt = AcpPluginSettings.getInstance().registryLastRefreshMillis.takeIf { it > 0 }
            ?.let { REFRESH_TIME_FORMATTER.format(Instant.ofEpochMilli(it)) }
            ?: MyBundle.message("settings.never")
        val legacyCount = installedAgents.count { it.isLegacy || it.registryAgentId.isBlank() }
        registryStatusLabel.text = MyBundle.message("settings.registryStatus", count, refreshedAt, legacyCount)
    }

    private fun updateActionButtons() {
        when (val selected = agentList.selectedValue) {
            is AgentRow.Registry -> {
                val isInstalled = selected.installed != null
                val upgradeAvailable = isInstalled && selected.installed.installedVersion != selected.agent.version
                installButton.isEnabled = !isInstalled
                upgradeButton.isEnabled = upgradeAvailable
                uninstallButton.isEnabled = isInstalled
                openLinkButton.isEnabled = selected.agent.website != null || selected.agent.repository != null
            }

            is AgentRow.Legacy -> {
                installButton.isEnabled = false
                upgradeButton.isEnabled = false
                uninstallButton.isEnabled = true
                openLinkButton.isEnabled = false
            }

            else -> {
                installButton.isEnabled = false
                upgradeButton.isEnabled = false
                uninstallButton.isEnabled = false
                openLinkButton.isEnabled = false
            }
        }
    }

    private sealed interface AgentRow {
        val title: String
        val status: String
        val detail: String

        data class Registry(
            val agent: AcpAgentRegistryService.RegistryAgent,
            val installed: AcpPluginSettings.InstalledAgentSetting?,
        ) : AgentRow {
            override val title: String = agent.name
            override val status: String =
                when {
                    installed == null -> MyBundle.message("settings.notInstalled")
                    installed.installedVersion.isBlank() -> MyBundle.message("settings.installed")
                    installed.installedVersion == agent.version -> MyBundle.message("settings.installedVersion", installed.installedVersion)
                    else -> MyBundle.message("settings.installedVersionLatest", installed.installedVersion, agent.version)
                }
            override val detail: String = buildString {
                append(agent.description)
                val method = agent.distribution.primaryInstallMethod()?.name?.lowercase()
                if (!method.isNullOrBlank()) {
                    if (isNotBlank()) append(" • ")
                    append(method)
                }
            }
        }

        data class Legacy(
            val installed: AcpPluginSettings.InstalledAgentSetting,
        ) : AgentRow {
            override val title: String = installed.displayName
            override val status: String = MyBundle.message("settings.legacyInstalled")
            override val detail: String = buildString {
                if (installed.description.isNotBlank()) {
                    append(installed.description)
                }
                if (installed.command.isNotBlank()) {
                    if (isNotBlank()) append(" • ")
                    append(installed.command)
                }
            }
        }
    }
}
