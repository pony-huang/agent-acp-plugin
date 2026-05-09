package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpAgentRegistryService

internal sealed interface AgentItem {
    val key: String
    val title: String
    val status: String
    val detail: String
    val searchText: String
    val searchPriority: Int

    fun descriptionText(): String
    fun listStatusText(): String?

    data class Registry(
        val agent: AcpAgentRegistryService.RegistryAgent,
        val installed: AcpPluginSettings.InstalledAgentSetting?,
    ) : AgentItem {
        override val key: String = "registry:${agent.id}"
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
            agent.distribution.primaryInstallMethod()?.name?.lowercase()?.takeIf { it.isNotBlank() }?.let { method ->
                if (isNotBlank()) {
                    append(" • ")
                }
                append(method)
            }
        }
        override val searchText: String = listOf(
            agent.name,
            agent.description,
            status,
            detail,
            agent.authors.joinToString(" "),
            agent.license.orEmpty(),
            agent.repository.orEmpty(),
            agent.website.orEmpty(),
            installed?.command.orEmpty(),
            installed?.args?.joinToString(" ").orEmpty(),
        ).joinToString(" ").lowercase()
        override val searchPriority: Int
            get() = when {
                upgradeAvailable -> 0
                isInstalled -> 1
                else -> 2
            }

        val isInstalled: Boolean
            get() = installed != null

        val upgradeAvailable: Boolean
            get() = installed != null && installed.installedVersion != agent.version

        val link: String?
            get() = agent.website ?: agent.repository

        override fun descriptionText(): String {
            return agent.description.ifBlank { MyBundle.message("agents.installedDefaultDescription") }
        }

        override fun listStatusText(): String? {
            return if (isInstalled) status else null
        }
    }

    data class Legacy(
        val installed: AcpPluginSettings.InstalledAgentSetting,
    ) : AgentItem {
        override val key: String = "legacy:${installed.displayName}"
        override val title: String = installed.displayName
        override val status: String = MyBundle.message("settings.legacyInstalled")
        override val detail: String = buildString {
            if (installed.description.isNotBlank()) {
                append(installed.description)
            }
            if (installed.command.isNotBlank()) {
                if (isNotBlank()) {
                    append(" • ")
                }
                append(installed.command)
            }
        }
        override val searchText: String = listOf(
            installed.displayName,
            installed.description,
            installed.command,
            installed.args.joinToString(" "),
            installed.sourceLabel,
            status,
            detail,
        ).joinToString(" ").lowercase()
        override val searchPriority: Int = 3

        override fun descriptionText(): String {
            return installed.description.ifBlank {
                if (installed.command.isNotBlank()) installed.command else MyBundle.message("agents.description.legacy")
            }
        }

        override fun listStatusText(): String = status
    }
}
