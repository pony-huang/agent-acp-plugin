package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Tag
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plugin settings for ACP Chat.
 * Uses PersistentStateComponent for IDE-independent persistence.
 */
@State(name = "ACPChatSettings", storages = [Storage("acp-chat-settings.xml")])
class AcpPluginSettings : PersistentStateComponent<AcpPluginSettings> {

    // Preferred installed agent configurations.
    var installedAgents: CopyOnWriteArrayList<InstalledAgentSetting> = CopyOnWriteArrayList()

    // Obsolete legacy settings retained only so older XML can be deserialized and discarded.
    var agentSettings: CopyOnWriteArrayList<LegacyAgentSetting> = CopyOnWriteArrayList()

    // Registry metadata
    var registryLastRefreshMillis: Long = 0L

    data class InstalledAgentSetting(
        @Tag("registryAgentId")
        var registryAgentId: String = "",

        @Tag("displayName")
        var displayName: String = "",

        @Tag("installMethod")
        var installMethod: InstallMethod = InstallMethod.NPX,

        @Tag("command")
        var command: String = "",

        @Tag("args")
        var args: List<String> = emptyList(),

        @Tag("env")
        var env: Map<String, String> = emptyMap(),

        @Tag("installedVersion")
        var installedVersion: String = "",

        @Tag("installRoot")
        var installRoot: String = "",

        @Tag("sourceLabel")
        var sourceLabel: String = "",

        @Tag("description")
        var description: String = "",

        @Tag("isLegacy")
        var isLegacy: Boolean = false,
    )

    data class LegacyAgentSetting(
        @Tag("name")
        var name: String = "",

        @Tag("command")
        var command: String = "",

        @Tag("args")
        var args: List<String> = emptyList(),

        @Tag("env")
        var env: Map<String, String> = emptyMap()
    )

    fun copy(): AcpPluginSettings {
        return AcpPluginSettings().also { snapshot ->
            snapshot.installedAgents = CopyOnWriteArrayList(installedAgents.map(::copyInstalledAgent))
            snapshot.agentSettings = CopyOnWriteArrayList()
            snapshot.registryLastRefreshMillis = registryLastRefreshMillis
        }
    }

    override fun getState(): AcpPluginSettings = copy()

    override fun loadState(state: AcpPluginSettings) {
        installedAgents = CopyOnWriteArrayList(state.installedAgents.map(::copyInstalledAgent))
        agentSettings = CopyOnWriteArrayList()
        registryLastRefreshMillis = state.registryLastRefreshMillis
    }

    fun getInstalledAgent(displayName: String): InstalledAgentSetting? {
        return installedAgents.find { it.displayName == displayName }
    }

    fun getInstalledAgentByRegistryId(registryAgentId: String): InstalledAgentSetting? {
        return installedAgents.find { it.registryAgentId == registryAgentId && !it.isLegacy }
    }

    fun saveInstalledAgent(setting: InstalledAgentSetting) {
        val snapshotSetting = copyInstalledAgent(setting)
        val existingIndex = installedAgents.indexOfFirst { existing ->
            when {
                snapshotSetting.registryAgentId.isNotBlank() -> existing.registryAgentId == snapshotSetting.registryAgentId
                else -> existing.displayName == snapshotSetting.displayName
            }
        }
        if (existingIndex >= 0) {
            installedAgents[existingIndex] = snapshotSetting
        } else {
            installedAgents.add(snapshotSetting)
        }
    }

    fun removeInstalledAgentByRegistryId(registryAgentId: String) {
        installedAgents.removeIf { it.registryAgentId == registryAgentId }
    }

    fun removeInstalledAgent(displayName: String) {
        installedAgents.removeIf { it.displayName == displayName }
    }

    fun updateRegistryRefreshTimestamp(timestampMillis: Long) {
        registryLastRefreshMillis = timestampMillis
    }

    companion object {
        fun getInstance(): AcpPluginSettings {
            return ApplicationManager.getApplication().getService(AcpPluginSettings::class.java)
        }
    }

    private fun copyInstalledAgent(setting: InstalledAgentSetting): InstalledAgentSetting {
        return setting.copy(
            args = setting.args.toList(),
            env = setting.env.toMap(),
        )
    }

}
