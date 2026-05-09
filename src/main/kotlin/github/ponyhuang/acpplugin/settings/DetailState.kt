package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.services.AcpAgentRegistryService

internal data class GroupDefinition(
    val title: String,
    val items: List<AgentItem>,
)

internal data class DetailState(
    val title: String,
    val status: String,
    val description: String,
    val overviewLines: List<String>,
    val detailLines: List<String>,
    val installEnabled: Boolean,
    val upgradeEnabled: Boolean,
    val uninstallEnabled: Boolean,
    val openLinkEnabled: Boolean,
    val openLink: String?,
    val registryAgent: AcpAgentRegistryService.RegistryAgent?,
    val uninstallTarget: AcpPluginSettings.InstalledAgentSetting?,
)
