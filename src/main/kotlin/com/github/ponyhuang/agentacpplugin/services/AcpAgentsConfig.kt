package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.nio.file.Path

/**
 * Service for managing ACP agents configuration with hot-reload support.
 * Inspired by acp-ui's config store with onConfigChanged listener.
 */
@Service(Service.Level.PROJECT)
class AcpAgentsConfigService(private val project: Project) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private var cachedConfig: JsonObject? = null

    // Config change notifier for hot-reload
    private val _configChanges = MutableSharedFlow<AgentsConfig>(replay = 1, extraBufferCapacity = 16)
    val configChanges: SharedFlow<AgentsConfig> = _configChanges.asSharedFlow()

    // Config file path (can be set externally for file watching)
    private var configFilePath: Path? = null

    // Hot-reload polling job
    private var hotReloadJob: kotlinx.coroutines.Job? = null
    private var lastConfigHash: Int = 0

    private fun parseConfig(): JsonObject {
        if (cachedConfig == null) {
            cachedConfig = json.parseToJsonElement(config)
                .jsonObject["agents"]
                ?.jsonObject
                ?: buildJsonObject { }
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

    /**
     * Start hot-reload monitoring for config changes.
     * Checks config file every [intervalMs] milliseconds.
     */
    fun startHotReload(intervalMs: Long = 2000) {
        stopHotReload()
        hotReloadJob = CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (isActive) {
                delay(intervalMs)
                checkConfigChanged()
            }
        }
        println("[AcpAgentsConfigService] Hot-reload started (interval: ${intervalMs}ms)")
    }

    /**
     * Stop hot-reload monitoring.
     */
    fun stopHotReload() {
        hotReloadJob?.cancel()
        hotReloadJob = null
        println("[AcpAgentsConfigService] Hot-reload stopped")
    }

    private fun checkConfigChanged() {
        // Compare current config hash with last known hash
        val currentHash = config.hashCode()
        if (currentHash != lastConfigHash && lastConfigHash != 0) {
            println("[AcpAgentsConfigService] Config change detected, notifying subscribers")
            // Clear cache to force re-parse
            cachedConfig = null
            notifyConfigChange()
        }
        lastConfigHash = currentHash
    }

    /**
     * Update config and notify all subscribers.
     * Called when config is modified externally.
     */
    fun updateConfig(newConfig: String) {
        config = newConfig
        cachedConfig = null  // Clear cache
        lastConfigHash = config.hashCode()
        notifyConfigChange()
        println("[AcpAgentsConfigService] Config updated and subscribers notified")
    }

    /**
     * Reload config from current source and notify if changed.
     */
    fun reload(): Boolean {
        val oldHash = lastConfigHash
        cachedConfig = null  // Clear cache
        lastConfigHash = config.hashCode()
        if (oldHash != lastConfigHash) {
            notifyConfigChange()
            println("[AcpAgentsConfigService] Config reloaded and subscribers notified")
            return true
        }
        return false
    }

    private fun notifyConfigChange() {
        val agentsConfig = AgentsConfig(
            agents = parseConfig().mapValues { (_, value) ->
                val obj = value.jsonObject
                AgentConfig(
                    command = obj["command"]?.jsonPrimitive?.content ?: "",
                    args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    env = obj["env"]?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.content } ?: emptyMap()
                )
            }
        )
        _configChanges.tryEmit(agentsConfig)
    }

    /**
     * Get current config as [AgentsConfig] object.
     */
    fun getConfig(): AgentsConfig {
        return AgentsConfig(
            agents = parseConfig().mapValues { (_, value) ->
                val obj = value.jsonObject
                AgentConfig(
                    command = obj["command"]?.jsonPrimitive?.content ?: "",
                    args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    env = obj["env"]?.jsonObject?.entries?.associate { it.key to it.value.jsonPrimitive.content } ?: emptyMap()
                )
            }
        )
    }

    /**
     * Get config file path.
     */
    fun getConfigPath(): String {
        return configFilePath?.toString() ?: "in-memory config"
    }

    /**
     * Set config file path for file-based watching (future use).
     */
    fun setConfigPath(path: String) {
        configFilePath = Path.of(path)
    }

    fun createClientBridge(
        agentName: String,
        coroutineScope: CoroutineScope,
        sessionUpdateSink: suspend (com.agentclientprotocol.model.SessionUpdate) -> Unit,
        permissionRequestSink: suspend (
            com.agentclientprotocol.model.SessionUpdate.ToolCallUpdate,
            List<com.agentclientprotocol.model.PermissionOption>,
            JsonElement?
        ) -> com.agentclientprotocol.model.RequestPermissionResponse,
    ): AcpAgentClient? {
        val config = getAgentConfig(agentName) ?: return null
        val cmd = listOf(config.command) + config.args
        val envs = config.env.map { "${it.key}=${it.value}" }
        return AcpAgentClient(
            coroutineScope = coroutineScope,
            project = project,
            cmd = cmd,
            envs = envs,
            sessionUpdateSink = sessionUpdateSink,
            permissionRequestSink = permissionRequestSink
        )
    }

    /**
     * Config change event data class.
     */
    data class AgentsConfig(
        val agents: Map<String, AgentConfig>
    )

    data class AgentConfig(
        val command: String,
        val args: List<String>,
        val env: Map<String, String>
    )

    var config: String = """
        {
          "agents": {
            "GitHub Copilot": {
              "command": "npx.cmd",
              "args": ["@github/copilot-language-server@latest", "--acp"],
              "env": {}
            },
            "Claude Code": {
              "command": "npx.cmd",
              "args": ["@zed-industries/claude-code-acp@latest"],
              "env": {
                "ANTHROPIC_API_KEY": "sk-ant-..."
              }
            },
            "Gemini CLI": {
              "command": "npx.cmd",
              "args": ["@google/gemini-cli@latest", "--experimental-acp"],
              "env": {}
            },
            "Qwen Code": {
              "command": "npx.cmd",
              "args": ["@qwen-code/qwen-code@latest", "--acp", "--experimental-skills"],
              "env": {}
            },
            "Auggie CLI": {
              "command": "npx.cmd",
              "args": ["@augmentcode/auggie@latest", "--acp"],
              "env": {"AUGMENT_DISABLE_AUTO_UPDATE": "1"}
            },
            "Qoder CLI": {
              "command": "npx.cmd",
              "args": ["@qoder-ai/qodercli@latest", "--acp"],
              "env": {}
            },
            "Codex CLI": {
              "command": "npx.cmd",
              "args": ["@zed-industries/codex-acp@latest"],
              "env": {}
            },
            "OpenCode": {
              "command": "opencode.cmd",
              "args": ["acp"],
              "env": {}
            },
            "OpenClaw": {
              "command": "npx.cmd",
              "args": ["openclaw", "acp"],
              "env": {}
            }
          }
        }
    """.trimIndent()
}
