package com.github.ponyhuang.agentacpplugin.services

/**
 * Registry facade for installed ACP agents.
 */
object AgentRegistry {

    data class InstalledAgent(
        val registryAgentId: String,
        val id: String,
        val displayName: String,
        val description: String,
        val version: String,
        val iconPath: String?,
        val installMethod: InstallMethod,
        val sourceLabel: String,
        val command: String,
        val args: List<String>,
        val env: Map<String, String>,
        val isLegacy: Boolean,
    )

    fun getAvailableAgents(configService: AcpAgentsConfigService): List<InstalledAgent> {
        return configService.getInstalledAgents()
    }
}

interface AgentListener {
    fun onAgentSelected(agent: AgentRegistry.InstalledAgent)
    fun onAgentDeselected()
}

class AgentNotifier {
    private val listeners = mutableListOf<AgentListener>()

    fun addListener(listener: AgentListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AgentListener) {
        listeners.remove(listener)
    }

    fun notifyAgentSelected(agent: AgentRegistry.InstalledAgent) {
        listeners.forEach { it.onAgentSelected(agent) }
    }

    fun notifyAgentDeselected() {
        listeners.forEach { it.onAgentDeselected() }
    }
}
