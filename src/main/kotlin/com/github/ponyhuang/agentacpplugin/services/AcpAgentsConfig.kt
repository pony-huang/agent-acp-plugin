package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * @author: pony
 * @date: Created in 15:53 2026/4/15
 */
@Service(Service.Level.PROJECT)
class AcpAgentsConfigService(private val project: Project) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AgentsConfig(val agents: JsonObject)

    private var cachedConfig: JsonObject? = null

    private fun parseConfig(): JsonObject {
        if (cachedConfig == null) {
            cachedConfig = json.decodeFromString<AgentsConfig>(config).agents
        }
        return cachedConfig!!
    }

    fun getAgentNames(): List<String> {
        return parseConfig().keys.toList()
    }

    fun getAgentConfig(agentName: String): AgentConfig? {
        val config = parseConfig()
        val agentJson = config[agentName]?.jsonObject ?: return null
        val command = agentJson["command"]?.jsonPrimitive?.content ?: return null
        val args = agentJson["args"]?.let { element ->
            element.jsonArray.map { it.jsonPrimitive.content }
        } ?: emptyList()
        val env = agentJson["env"]?.jsonObject?.entries?.associate {
            it.key to it.value.jsonPrimitive.content
        } ?: emptyMap()
        return AgentConfig(command, args, env)
    }

    fun createClientBridge(
        agentName: String,
        coroutineScope: CoroutineScope
    ): AcpAgentClient? {
        val config = getAgentConfig(agentName) ?: return null
        val cmd = listOf(config.command) + config.args
        val envs = config.env.map { "${it.key}=${it.value}" }
        return AcpAgentClient(
            coroutineScope = coroutineScope,
            project = project,
            cmd = cmd,
            envs = envs,
            sessionUpdateSink = { }
        )
    }

    data class AgentConfig(
        val command: String,
        val args: List<String>,
        val env: Map<String, String>
    )

    var config: String = """
        {
          "agents": {
            "GitHub Copilot": {
              "command": "npx",
              "args": ["@github/copilot-language-server@latest", "--acp"],
              "env": {}
            },
            "Claude Code": {
              "command": "npx",
              "args": ["@zed-industries/claude-code-acp@latest"],
              "env": {
                "ANTHROPIC_API_KEY": "sk-ant-..."
              }
            },
            "Gemini CLI": {
              "command": "npx",
              "args": ["@google/gemini-cli@latest", "--experimental-acp"],
              "env": {}
            },
            "Qwen Code": {
              "command": "npx",
              "args": ["@qwen-code/qwen-code@latest", "--acp", "--experimental-skills"],
              "env": {}
            },
            "Auggie CLI": {
              "command": "npx",
              "args": ["@augmentcode/auggie@latest", "--acp"],
              "env": {"AUGMENT_DISABLE_AUTO_UPDATE": "1"}
            },
            "Qoder CLI": {
              "command": "npx",
              "args": ["@qoder-ai/qodercli@latest", "--acp"],
              "env": {}
            },
            "Codex CLI": {
              "command": "npx",
              "args": ["@zed-industries/codex-acp@latest"],
              "env": {}
            },
            "OpenCode": {
              "command": "npx",
              "args": ["opencode-ai@latest", "acp"],
              "env": {}
            },
            "OpenClaw": {
              "command": "npx",
              "args": ["openclaw", "acp"],
              "env": {}
            }
          }
        }
    """.trimIndent()
}