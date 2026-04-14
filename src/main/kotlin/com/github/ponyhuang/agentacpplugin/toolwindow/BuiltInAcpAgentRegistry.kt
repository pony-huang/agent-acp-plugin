package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AcpAgentDescriptor

object BuiltInAcpAgentRegistry {

    data class AgentDefinition(
        val id: String,
        val displayName: String,
        val description: String,
        val command: String,
        val args: List<String>,
        val fixedEnv: Map<String, String> = emptyMap(),
        val requiredEnvKeys: List<String> = emptyList(),
    ) {
        fun toDescriptor(): AcpAgentDescriptor {
            val resolvedEnv = LinkedHashMap(fixedEnv)
            requiredEnvKeys.forEach { key ->
                val value = System.getenv(key)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "Agent '$displayName' requires environment variable '$key'."
                    )
                resolvedEnv[key] = value
            }

            return AcpAgentDescriptor(
                id = id,
                displayName = displayName,
                command = command,
                args = args,
                env = resolvedEnv,
            )
        }
    }

    val agents: List<AgentDefinition> = listOf(
        AgentDefinition(
            id = "claude-code",
            displayName = "Claude Code",
            description = "Claude Code ACP",
            command = "npx.cmd",
            args = listOf("@zed-industries/claude-code-acp@latest"),
        ),
        AgentDefinition(
            id = "codex-cli",
            displayName = "Codex CLI",
            description = "Codex ACP",
            command = "npx",
            args = listOf("@zed-industries/codex-acp@latest"),
        ),
        AgentDefinition(
            id = "open-code",
            displayName = "OpenCode",
            description = "OpenCode ACP",
            command = "npx",
            args = listOf("opencode-ai@latest", "acp"),
        )
    )
    fun defaultAgent(): AgentDefinition = agents.first()
}
