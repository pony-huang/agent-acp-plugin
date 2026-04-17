package com.github.ponyhuang.agentacpplugin.settings

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

    // Agent configurations (stored as list for serialization)
    var agentSettings: MutableList<AgentSetting> = mutableListOf()

    // General plugin settings
    var autoConnectEnabled: Boolean = false
    var showStartupNotifications: Boolean = true
    var sessionsStoragePath: String = ""

    data class AgentSetting(
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
        agentSettings = state.agentSettings.toMutableList()
        autoConnectEnabled = state.autoConnectEnabled
        showStartupNotifications = state.showStartupNotifications
        sessionsStoragePath = state.sessionsStoragePath
    }

    /**
     * Get agent configuration by name.
     */
    fun getAgent(name: String): AgentSetting? {
        return agentSettings.find { it.name == name }
    }

    /**
     * Add or update an agent configuration.
     */
    fun saveAgent(name: String, command: String, args: List<String>, env: Map<String, String>) {
        val existing = agentSettings.find { it.name == name }
        if (existing != null) {
            existing.command = command
            existing.args = args
            existing.env = env
        } else {
            agentSettings.add(AgentSetting(name, command, args, env))
        }
    }

    /**
     * Remove an agent configuration.
     */
    fun removeAgent(name: String) {
        agentSettings.removeAll { it.name == name }
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

    companion object {
        fun getInstance(): AcpPluginSettings {
            return ApplicationManager.getApplication().getService(AcpPluginSettings::class.java)
        }
    }
}