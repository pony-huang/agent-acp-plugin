package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpAgentIconService
import github.ponyhuang.acpplugin.services.AcpAgentRegistryService
import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

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

    private val rootPanel = JPanel(BorderLayout())
    private val agentDetailPanel = AgentDetailPanel(
        onInstallAgent = onInstallAgent,
        onUninstallAgent = onUninstallAgent,
    )
    private val agentBrowserTab = AgentBrowserTab(
        detailComponent = agentDetailPanel.component,
        onRefreshRegistry = onRefreshRegistry,
        onSelectionChanged = { item ->
            if (item == null) {
                agentDetailPanel.showEmpty()
            } else {
                agentDetailPanel.show(detailState(item))
            }
        },
        onInstallAgent = onInstallAgent,
        onUninstallAgent = onUninstallAgent,
        onOpenLink = onOpenLink,
        iconResolver = ::resolveAgentIcon,
    )
    private val agentIconService: AcpAgentIconService by lazy {
        ApplicationManager.getApplication().getService(AcpAgentIconService::class.java)
    }

    private var allAgentItems: List<AgentItem> = emptyList()
    private var registrySnapshot: AcpAgentRegistryService.RegistrySnapshot? = null

    init {
        rootPanel.border = JBUI.Borders.empty()
        rootPanel.add(agentBrowserTab.component, BorderLayout.CENTER)
    }

    fun getPanel(): JComponent = rootPanel

    fun getPreferredFocusedComponent(): JComponent = agentBrowserTab.getPreferredFocusedComponent()

    fun setRegistryData(
        snapshot: AcpAgentRegistryService.RegistrySnapshot?,
        installedAgents: List<AcpPluginSettings.InstalledAgentSetting>,
    ) {
        // Keep one normalized in-memory list so grouped view, search results, and detail panel all resolve from
        // the same source of truth.
        registrySnapshot = snapshot
        allAgentItems = buildAgentItems(snapshot, installedAgents)
        agentBrowserTab.setData(
            items = allAgentItems,
            statusText = officialStatusText(installedAgents),
        )
    }

    private fun buildAgentItems(
        snapshot: AcpAgentRegistryService.RegistrySnapshot?,
        installedAgents: List<AcpPluginSettings.InstalledAgentSetting>,
    ): List<AgentItem> {
        val installedByRegistryId = installedAgents
            .filter { it.registryAgentId.isNotBlank() && !it.isLegacy }
            .associateBy { it.registryAgentId }

        val registryItems = snapshot?.agents.orEmpty().map { agent ->
            AgentItem.Registry(agent, installedByRegistryId[agent.id])
        }
        val legacyItems = installedAgents
            .filter { it.isLegacy || it.registryAgentId.isBlank() }
            .sortedBy { it.displayName.lowercase() }
            .map { AgentItem.Legacy(it) }
        return registryItems + legacyItems
    }

    private fun resolveAgentIcon(item: AgentItem, size: Int): Icon {
        fun load(agentId: String, iconUrl: String?): Icon {
            val iconPath = agentIconService.resolveCachedIconPath(agentId, iconUrl)
            return agentIconService.loadIcon(iconPath, size = size)
        }

        return when (item) {
            is AgentItem.Registry -> load(item.agent.id, item.agent.icon)
            is AgentItem.Legacy -> {
                val registryAgent = registrySnapshot?.agents?.firstOrNull { it.id == item.installed.registryAgentId }
                load(item.installed.registryAgentId, registryAgent?.icon)
            }
        }
    }

    private fun officialStatusText(installedAgents: List<AcpPluginSettings.InstalledAgentSetting>): String {
        val count = registrySnapshot?.agents?.size ?: 0
        val refreshedAt = AcpPluginSettings.getInstance().registryLastRefreshMillis.takeIf { it > 0 }
            ?.let { REFRESH_TIME_FORMATTER.format(Instant.ofEpochMilli(it)) }
            ?: MyBundle.message("settings.never")
        val legacyCount = installedAgents.count { it.isLegacy || it.registryAgentId.isBlank() }
        return MyBundle.message("settings.registryStatus", count, refreshedAt, legacyCount)
    }

    private fun installMethodText(installMethod: InstallMethod?): String {
        return when (installMethod) {
            InstallMethod.NPX -> "NPX"
            InstallMethod.UVX -> "UVX"
            InstallMethod.BINARY -> "Binary"
            null -> "-"
        }
    }

    private fun distributionSummary(agent: AcpAgentRegistryService.RegistryAgent): String? {
        return when (agent.distribution.primaryInstallMethod()) {
            InstallMethod.NPX -> agent.distribution.npx?.let { distribution ->
                listOf(distribution.`package`) + distribution.args
            }?.joinToString(" ")

            InstallMethod.UVX -> agent.distribution.uvx?.let { distribution ->
                listOf(distribution.`package`) + distribution.args
            }?.joinToString(" ")

            InstallMethod.BINARY -> agent.distribution.binary.entries
                .sortedBy { it.key }
                .joinToString(" | ") { (platform, distribution) ->
                    "$platform: ${distribution.cmd}"
                }

            null -> null
        }
    }

    private fun detailState(item: AgentItem): DetailState {
        return when (item) {
            is AgentItem.Registry -> {
                val overview = buildList {
                    add(MyBundle.message("settings.detailLatestVersion", item.agent.version.ifBlank { "-" }))
                    add(MyBundle.message("settings.detailSource", item.installed?.sourceLabel?.ifBlank { MyBundle.message("agents.source.official") } ?: MyBundle.message("agents.source.official")))
                    add(MyBundle.message("settings.detailLegacy", MyBundle.message("settings.noValue")))
                }
                val details = buildList {
                    add(MyBundle.message("settings.detailAuthors", item.agent.authors.joinToString(", ").ifBlank { "-" }))
                    add(MyBundle.message("settings.detailLicense", item.agent.license.orDash()))
                    add(MyBundle.message("settings.detailInstallMethod", installMethodText(item.agent.distribution.primaryInstallMethod())))
                    add(MyBundle.message("settings.detailDistribution", distributionSummary(item.agent).orDash()))
                    add(MyBundle.message("settings.detailInstallRoot", item.installed?.installRoot.orDash()))
                    add(MyBundle.message("settings.detailCommand", item.installed?.command.orDash()))
                    add(MyBundle.message("settings.detailArguments", item.installed?.args?.joinToString(" ").orDash()))
                    add(MyBundle.message("settings.detailRepository", item.agent.repository.orDash()))
                    add(MyBundle.message("settings.detailWebsite", item.agent.website.orDash()))
                }
                DetailState(
                    title = item.title,
                    status = item.status,
                    description = item.descriptionText(),
                    overviewLines = overview,
                    detailLines = details,
                    installEnabled = !item.isInstalled,
                    upgradeEnabled = item.upgradeAvailable,
                    uninstallEnabled = item.isInstalled,
                    openLinkEnabled = item.link != null,
                    openLink = item.link,
                    registryAgent = item.agent,
                    uninstallTarget = item.installed,
                )
            }

            is AgentItem.Legacy -> {
                val overview = buildList {
                    add(MyBundle.message("settings.detailSource", item.installed.sourceLabel.orDash()))
                    add(MyBundle.message("settings.detailLegacy", MyBundle.message("settings.yesValue")))
                }
                val details = buildList {
                    add(MyBundle.message("settings.detailAuthors", "-"))
                    add(MyBundle.message("settings.detailLicense", "-"))
                    add(MyBundle.message("settings.detailInstallMethod", installMethodText(item.installed.installMethod)))
                    add(MyBundle.message("settings.detailDistribution", "-"))
                    add(MyBundle.message("settings.detailInstallRoot", item.installed.installRoot.orDash()))
                    add(MyBundle.message("settings.detailCommand", item.installed.command.orDash()))
                    add(MyBundle.message("settings.detailArguments", item.installed.args.joinToString(" ").orDash()))
                    add(MyBundle.message("settings.detailRepository", "-"))
                    add(MyBundle.message("settings.detailWebsite", "-"))
                }
                DetailState(
                    title = item.title,
                    status = item.status,
                    description = item.descriptionText(),
                    overviewLines = overview,
                    detailLines = details,
                    installEnabled = false,
                    upgradeEnabled = false,
                    uninstallEnabled = true,
                    openLinkEnabled = false,
                    openLink = null,
                    registryAgent = null,
                    uninstallTarget = item.installed,
                )
            }
        }
    }

    private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "-"

    internal fun debugHasResolvedIcon(key: String): Boolean {
        val item = allAgentItems.firstOrNull { it.key == key } ?: return false
        return runCatching { resolveAgentIcon(item, JBUI.scale(20)) }.isSuccess
    }

    internal fun debugPrimaryActionText(key: String): String? {
        val item = allAgentItems.firstOrNull { it.key == key } ?: return null
        return agentBrowserTab.primaryActionText(item)
    }

    internal fun debugPrimaryStatusText(key: String): String? {
        return allAgentItems.firstOrNull { it.key == key }?.listStatusText()
    }
}
