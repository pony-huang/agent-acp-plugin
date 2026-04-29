package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Tag

/**
 * Plugin settings for ACP Chat.
 * Uses PersistentStateComponent for IDE-independent persistence.
 */
@State(name = "ACPChatSettings", storages = [Storage("acp-chat-settings.xml")])
class AcpPluginSettings : PersistentStateComponent<AcpPluginSettings> {

    // Preferred installed agent configurations.
    var installedAgents: MutableList<InstalledAgentSetting> = mutableListOf()

    // Legacy settings retained for compatibility and migration from earlier versions.
    var agentSettings: MutableList<LegacyAgentSetting> = mutableListOf()

    // General plugin settings
    var autoConnectEnabled: Boolean = false
    var showStartupNotifications: Boolean = true
    var sessionsStoragePath: String = ""

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

    override fun getState(): AcpPluginSettings = this

    override fun loadState(state: AcpPluginSettings) {
        installedAgents = state.installedAgents.toMutableList()
        agentSettings = state.agentSettings.toMutableList()
        autoConnectEnabled = state.autoConnectEnabled
        showStartupNotifications = state.showStartupNotifications
        sessionsStoragePath = state.sessionsStoragePath
        registryLastRefreshMillis = state.registryLastRefreshMillis

        migrateLegacyAgentsIfNeeded()
    }

    fun getInstalledAgent(displayName: String): InstalledAgentSetting? {
        return installedAgents.find { it.displayName == displayName }
    }

    fun getInstalledAgentByRegistryId(registryAgentId: String): InstalledAgentSetting? {
        return installedAgents.find { it.registryAgentId == registryAgentId && !it.isLegacy }
    }

    fun saveInstalledAgent(setting: InstalledAgentSetting) {
        val existingIndex = installedAgents.indexOfFirst { existing ->
            when {
                setting.registryAgentId.isNotBlank() -> existing.registryAgentId == setting.registryAgentId
                else -> existing.displayName == setting.displayName
            }
        }
        if (existingIndex >= 0) {
            installedAgents[existingIndex] = setting
        } else {
            installedAgents.add(setting)
        }
    }

    fun removeInstalledAgentByRegistryId(registryAgentId: String) {
        installedAgents.removeAll { it.registryAgentId == registryAgentId }
    }

    fun removeInstalledAgent(displayName: String) {
        installedAgents.removeAll { it.displayName == displayName }
    }

    fun updateRegistryRefreshTimestamp(timestampMillis: Long) {
        registryLastRefreshMillis = timestampMillis
    }

    /**
     * Get the effective sessions storage path.
     */
    fun getEffectiveSessionsPath(): String {
        return sessionsStoragePath.ifBlank { getDefaultSessionsPath() }
    }

    private fun getDefaultSessionsPath(): String {
        val configPath = System.getProperty("idea.config.path")
        return if (configPath != null) {
            "$configPath/ACPChat"
        } else {
            "${System.getProperty("user.home")}/.acpchat"
        }
    }

    private fun migrateLegacyAgentsIfNeeded() {
        if (agentSettings.isEmpty()) {
            return
        }
        if (installedAgents.isNotEmpty()) {
            agentSettings.clear()
            return
        }

        val migratedAgents = agentSettings.map { legacy ->
            val guess = LegacyAgentMapping.guess(legacy.name, legacy.command, legacy.args)
            InstalledAgentSetting(
                registryAgentId = guess.registryAgentId.orEmpty(),
                displayName = legacy.name,
                installMethod = guess.installMethod ?: guessInstallMethod(legacy.command, legacy.args),
                command = legacy.command,
                args = legacy.args,
                env = legacy.env,
                installedVersion = "",
                installRoot = "",
                sourceLabel = if (guess.registryAgentId != null) MyBundle.message("agents.source.migrated") else MyBundle.message("agents.source.legacy"),
                description = if (guess.registryAgentId != null) MyBundle.message("agents.description.migrated") else MyBundle.message("agents.description.legacy") ,
                isLegacy = guess.registryAgentId == null
            )
        }

        installedAgents.addAll(migratedAgents)
        agentSettings.clear()
    }

    private fun guessInstallMethod(command: String, args: List<String>): InstallMethod {
        val normalizedCommand = command.lowercase()
        return when {
            normalizedCommand.contains("uvx") -> InstallMethod.UVX
            normalizedCommand.contains("npx") -> InstallMethod.NPX
            args.any { it.contains("--acp") || it.contains("acp") } -> InstallMethod.BINARY
            else -> InstallMethod.NPX
        }
    }

    companion object {
        fun getInstance(): AcpPluginSettings {
            return ApplicationManager.getApplication().getService(AcpPluginSettings::class.java)
        }
    }
}

private object LegacyAgentMapping {
    data class Guess(
        val registryAgentId: String?,
        val installMethod: InstallMethod?,
    )

    private val displayNameMappings = mapOf(
        "GitHub Copilot" to Guess("github-copilot-cli", InstallMethod.NPX),
        "Claude Code" to Guess("claude-acp", InstallMethod.NPX),
        "Gemini CLI" to Guess("gemini", InstallMethod.NPX),
        "Qwen Code" to Guess("qwen-code", InstallMethod.NPX),
        "Auggie CLI" to Guess("auggie", InstallMethod.NPX),
        "Qoder CLI" to Guess("qoder", InstallMethod.NPX),
        "Codex CLI" to Guess("codex-acp", InstallMethod.NPX),
        "OpenCode" to Guess("opencode", InstallMethod.BINARY),
    )

    fun guess(name: String, command: String, args: List<String>): Guess {
        displayNameMappings[name]?.let { return it }
        val joinedArgs = args.joinToString(" ").lowercase()
        return when {
            "@github/copilot" in joinedArgs -> Guess("github-copilot-cli", InstallMethod.NPX)
            "@agentclientprotocol/claude-agent-acp" in joinedArgs || "claude-code-acp" in joinedArgs -> Guess("claude-acp", InstallMethod.NPX)
            "@google/gemini-cli" in joinedArgs -> Guess("gemini", InstallMethod.NPX)
            "@qwen-code/qwen-code" in joinedArgs -> Guess("qwen-code", InstallMethod.NPX)
            "@augmentcode/auggie" in joinedArgs -> Guess("auggie", InstallMethod.NPX)
            "@qoder-ai/qodercli" in joinedArgs -> Guess("qoder", InstallMethod.NPX)
            "@zed-industries/codex-acp" in joinedArgs -> Guess("codex-acp", InstallMethod.NPX)
            "opencode" in command.lowercase() || "opencode" in joinedArgs -> Guess("opencode", InstallMethod.BINARY)
            else -> Guess(null, null)
        }
    }
}
