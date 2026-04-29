package github.ponyhuang.acpplugin.services

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.settings.AcpPluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing effective ACP agent configuration.
 * The effective config is built from the list of installed agents stored in settings.
 */
@Service(Service.Level.PROJECT)
class AcpAgentsConfigService(private val project: Project) {
    private val logger = Logger.getInstance(AcpAgentsConfigService::class.java)

    private val settings: AcpPluginSettings
        get() = AcpPluginSettings.getInstance()

    private val registryService: AcpAgentRegistryService
        get() = ApplicationManager.getApplication().getService(AcpAgentRegistryService::class.java)
    private val agentIconService: AcpAgentIconService
        get() = ApplicationManager.getApplication().getService(AcpAgentIconService::class.java)

    private val _configChanges = MutableSharedFlow<AgentsConfig>(replay = 1, extraBufferCapacity = 16)
    val configChanges: SharedFlow<AgentsConfig> = _configChanges.asSharedFlow()

    fun getInstalledAgents(): List<AgentRegistry.InstalledAgent> {
        return settings.installedAgents.map { installed ->
            val registryAgent = registryService.findAgentById(installed.registryAgentId)
            AgentRegistry.InstalledAgent(
                registryAgentId = installed.registryAgentId,
                id = if (installed.registryAgentId.isNotBlank()) installed.registryAgentId else installed.displayName.lowercase().replace(" ", "-"),
                displayName = installed.displayName,
                description = registryAgent?.description ?: installed.description.ifBlank { MyBundle.message("agents.installedDefaultDescription") },
                version = installed.installedVersion.ifBlank { registryAgent?.version.orEmpty() },
                iconPath = agentIconService.resolveCachedIconPath(installed.registryAgentId, registryAgent?.icon),
                installMethod = installed.installMethod,
                sourceLabel = installed.sourceLabel.ifBlank {
                    if (installed.isLegacy) MyBundle.message("agents.source.legacy") else MyBundle.message("agents.source.official")
                },
                command = installed.command,
                args = installed.args,
                env = installed.env,
                isLegacy = installed.isLegacy,
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    fun getAgentNames(): List<String> {
        return settings.installedAgents.map { it.displayName }.sorted()
    }

    fun getAgentConfig(agentName: String): AgentConfig? {
        val installed = settings.getInstalledAgent(agentName) ?: return null
        return AgentConfig(
            command = installed.command,
            args = installed.args,
            env = installed.env
        )
    }

    fun notifyInstalledAgentsChanged() {
        _configChanges.tryEmit(getConfig())
    }

    fun getConfig(): AgentsConfig {
        return AgentsConfig(
            agents = settings.installedAgents.associate { installed ->
                installed.displayName to AgentConfig(
                    command = installed.command,
                    args = installed.args,
                    env = installed.env
                )
            }
        )
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
        logger.info(
            "[AgentClient] createClientBridge: agentName=$agentName, command=${config.command}, " +
                "args=${config.args}, envKeys=${config.env.keys.sorted()}"
        )
        return AcpAgentClient(
            coroutineScope = coroutineScope,
            project = project,
            cmd = cmd,
            envs = envs,
            sessionUpdateSink = sessionUpdateSink,
            permissionRequestSink = permissionRequestSink
        )
    }

    data class AgentsConfig(
        val agents: Map<String, AgentConfig>
    )

    data class AgentConfig(
        val command: String,
        val args: List<String>,
        val env: Map<String, String>
    )
}
