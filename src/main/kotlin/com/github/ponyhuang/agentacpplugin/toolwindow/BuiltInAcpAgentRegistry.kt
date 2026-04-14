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
            id = "github-copilot",
            displayName = "GitHub Copilot",
            description = "GitHub Copilot ACP via language server",
            command = "npx",
            args = listOf("@github/copilot-language-server@latest", "--acp"),
        ),
        AgentDefinition(
            id = "claude-code",
            displayName = "Claude Code",
            description = "Claude Code ACP",
            command = "npx",
            args = listOf("@zed-industries/claude-code-acp@latest"),
            requiredEnvKeys = listOf("ANTHROPIC_API_KEY"),
        ),
        AgentDefinition(
            id = "gemini-cli",
            displayName = "Gemini CLI",
            description = "Google Gemini CLI ACP",
            command = "npx",
            args = listOf("@google/gemini-cli@latest", "--experimental-acp"),
        ),
        AgentDefinition(
            id = "qwen-code",
            displayName = "Qwen Code",
            description = "Qwen Code ACP",
            command = "npx",
            args = listOf("@qwen-code/qwen-code@latest", "--acp", "--experimental-skills"),
        ),
        AgentDefinition(
            id = "auggie-cli",
            displayName = "Auggie CLI",
            description = "Auggie CLI ACP",
            command = "npx",
            args = listOf("@augmentcode/auggie@latest", "--acp"),
            fixedEnv = mapOf("AUGMENT_DISABLE_AUTO_UPDATE" to "1"),
        ),
        AgentDefinition(
            id = "qoder-cli",
            displayName = "Qoder CLI",
            description = "Qoder CLI ACP",
            command = "npx",
            args = listOf("@qoder-ai/qodercli@latest", "--acp"),
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
        ),
        AgentDefinition(
            id = "open-claw",
            displayName = "OpenClaw",
            description = "OpenClaw ACP",
            command = "npx",
            args = listOf("openclaw", "acp"),
        ),
    )

    fun defaultAgent(): AgentDefinition = agents.first()
}
