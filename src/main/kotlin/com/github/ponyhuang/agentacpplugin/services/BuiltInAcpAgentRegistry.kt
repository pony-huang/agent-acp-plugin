package com.github.ponyhuang.agentacpplugin.services

/**
 * Registry for built-in ACP agents.
 * Maps display names to agent configuration for launching via npx.
 */
object BuiltInAcpAgentRegistry {

    /**
     * Represents a built-in agent definition.
     */
    data class AgentDefinition(
        val id: String,
        val displayName: String,
        val description: String,
        val command: String,
        val args: List<String>,
        val env: Map<String, String>
    )

    /**
     * Returns list of available built-in agent definitions from config.
     */
    fun getAvailableAgents(configService: AcpAgentsConfigService): List<AgentDefinition> {
        return configService.getAgentNames().mapNotNull { name ->
            configService.getAgentConfig(name)?.let { config ->
                AgentDefinition(
                    id = name.lowercase().replace(" ", "-"),
                    displayName = name,
                    description = "Launch $name via ACP",
                    command = config.command,
                    args = config.args,
                    env = config.env
                )
            }
        }
    }
}

/**
 * Agent selection change listener.
 */
interface AgentSelectionListener {
    fun onAgentSelected(agent: BuiltInAcpAgentRegistry.AgentDefinition)
    fun onAgentDeselected()
}

/**
 * Notifier for agent selection changes.
 * Notifies Model and Plan ComboBoxes when agent selection changes.
 */
class AgentSelectionNotifier {
    private val listeners = mutableListOf<AgentSelectionListener>()

    fun addListener(listener: AgentSelectionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AgentSelectionListener) {
        listeners.remove(listener)
    }

    fun notifyAgentSelected(agent: BuiltInAcpAgentRegistry.AgentDefinition) {
        listeners.forEach { it.onAgentSelected(agent) }
    }

    fun notifyAgentDeselected() {
        listeners.forEach { it.onAgentDeselected() }
    }
}
