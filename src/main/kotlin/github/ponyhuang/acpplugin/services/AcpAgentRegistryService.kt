package github.ponyhuang.acpplugin.services

import github.ponyhuang.acpplugin.settings.AcpPluginSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class InstallMethod {
    NPX,
    UVX,
    BINARY
}

@Service(Service.Level.APP)
class AcpAgentRegistryService {
    companion object {
        const val REGISTRY_URL: String = "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"
    }

    data class RegistrySnapshot(
        val version: String,
        val agents: List<RegistryAgent>,
        val refreshedAtMillis: Long,
    )

    data class RegistryAgent(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val repository: String?,
        val website: String?,
        val authors: List<String>,
        val license: String?,
        val icon: String?,
        val distribution: AgentDistribution,
    )

    data class AgentDistribution(
        val npx: CommandDistribution? = null,
        val uvx: CommandDistribution? = null,
        val binary: Map<String, BinaryDistribution> = emptyMap(),
    ) {
        fun primaryInstallMethod(): InstallMethod? {
            return when {
                npx != null -> InstallMethod.NPX
                uvx != null -> InstallMethod.UVX
                binary.isNotEmpty() -> InstallMethod.BINARY
                else -> null
            }
        }
    }

    data class CommandDistribution(
        val `package`: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
    )

    data class BinaryDistribution(
        val archive: String,
        val cmd: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
    )

    private val logger = Logger.getInstance(AcpAgentRegistryService::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Volatile
    private var cachedSnapshot: RegistrySnapshot? = null

    fun getSnapshot(forceRefresh: Boolean = false): RegistrySnapshot {
        if (!forceRefresh) {
            cachedSnapshot?.let { return it }
            loadSnapshotFromDisk()?.let {
                cachedSnapshot = it
                return it
            }
        }

        val snapshot = fetchSnapshotFromRemote()
        cachedSnapshot = snapshot
        saveSnapshotToDisk(snapshot)
        AcpPluginSettings.getInstance().updateRegistryRefreshTimestamp(snapshot.refreshedAtMillis)
        return snapshot
    }

    fun getCachedSnapshotOrNull(): RegistrySnapshot? {
        cachedSnapshot?.let { return it }
        return loadSnapshotFromDisk()?.also { cachedSnapshot = it }
    }

    fun findAgentById(registryAgentId: String): RegistryAgent? {
        if (registryAgentId.isBlank()) {
            return null
        }
        return getCachedSnapshotOrNull()?.agents?.find { it.id == registryAgentId }
    }

    internal fun replaceSnapshotForTests(snapshot: RegistrySnapshot?) {
        cachedSnapshot = snapshot
    }

    private fun fetchSnapshotFromRemote(): RegistrySnapshot {
        val request = HttpRequest.newBuilder(URI.create(REGISTRY_URL))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("Failed to fetch ACP registry: HTTP ${response.statusCode()}")
        }
        return parseSnapshot(response.body(), System.currentTimeMillis())
    }

    private fun loadSnapshotFromDisk(): RegistrySnapshot? {
        val cacheFile = cacheFile()
        if (!cacheFile.exists()) {
            return null
        }
        return runCatching {
            parseSnapshot(cacheFile.readText(), Files.getLastModifiedTime(cacheFile).toMillis())
        }.onFailure { error ->
            logger.warn("Failed to load ACP registry cache from $cacheFile", error)
        }.getOrNull()
    }

    private fun saveSnapshotToDisk(snapshot: RegistrySnapshot) {
        val cacheFile = cacheFile()
        cacheFile.parent?.createDirectories()
        cacheFile.writeText(
            JsonObject(
                mapOf(
                    "version" to stringElement(snapshot.version),
                    "agents" to snapshot.agents.agentsToJson()
                )
            ).toString()
        )
    }

    private fun parseSnapshot(content: String, refreshedAtMillis: Long): RegistrySnapshot {
        val root = json.parseToJsonElement(content).jsonObject
        val version = root["version"]?.jsonPrimitive?.content.orEmpty()
        val agents = root["agents"]?.jsonArray?.mapNotNull { parseAgent(it.jsonObject) }.orEmpty()
        return RegistrySnapshot(
            version = version,
            agents = agents.sortedBy { it.name.lowercase() },
            refreshedAtMillis = refreshedAtMillis,
        )
    }

    private fun parseAgent(obj: JsonObject): RegistryAgent? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val distributionObject = obj["distribution"]?.jsonObject ?: return null
        return RegistryAgent(
            id = id,
            name = name,
            version = obj["version"]?.jsonPrimitive?.content.orEmpty(),
            description = obj["description"]?.jsonPrimitive?.content.orEmpty(),
            repository = obj["repository"].stringOrNull(),
            website = obj["website"].stringOrNull(),
            authors = obj["authors"]?.jsonArray?.mapNotNull { it.stringOrNull() }.orEmpty(),
            license = obj["license"].stringOrNull(),
            icon = obj["icon"].stringOrNull(),
            distribution = AgentDistribution(
                npx = distributionObject["npx"]?.jsonObject?.let(::parseCommandDistribution),
                uvx = distributionObject["uvx"]?.jsonObject?.let(::parseCommandDistribution),
                binary = distributionObject["binary"]?.jsonObject?.entries?.associate { (platformKey, value) ->
                    platformKey to parseBinaryDistribution(value.jsonObject)
                }.orEmpty(),
            ),
        )
    }

    private fun parseCommandDistribution(obj: JsonObject): CommandDistribution {
        return CommandDistribution(
            `package` = obj["package"]?.jsonPrimitive?.content.orEmpty(),
            args = obj["args"]?.jsonArray?.mapNotNull { it.stringOrNull() }.orEmpty(),
            env = obj["env"]?.jsonObject?.entries?.mapNotNull { (key, value) ->
                value.stringOrNull()?.let { key to it }
            }?.toMap().orEmpty(),
        )
    }

    private fun parseBinaryDistribution(obj: JsonObject): BinaryDistribution {
        return BinaryDistribution(
            archive = obj["archive"]?.jsonPrimitive?.content.orEmpty(),
            cmd = obj["cmd"]?.jsonPrimitive?.content.orEmpty(),
            args = obj["args"]?.jsonArray?.mapNotNull { it.stringOrNull() }.orEmpty(),
            env = obj["env"]?.jsonObject?.entries?.mapNotNull { (key, value) ->
                value.stringOrNull()?.let { key to it }
            }?.toMap().orEmpty(),
        )
    }

    private fun cacheFile(): Path {
        return Path.of(PathManager.getConfigPath(), "ACPChat", "registry", "registry.json")
    }

    private fun List<RegistryAgent>.agentsToJson(): JsonArray {
        return JsonArray(map { agent ->
            JsonObject(
                buildMap {
                    put("id", stringElement(agent.id))
                    put("name", stringElement(agent.name))
                    put("version", stringElement(agent.version))
                    put("description", stringElement(agent.description))
                    agent.repository?.let { put("repository", stringElement(it)) }
                    agent.website?.let { put("website", stringElement(it)) }
                    agent.license?.let { put("license", stringElement(it)) }
                    agent.icon?.let { put("icon", stringElement(it)) }
                    put("authors", JsonArray(agent.authors.map(::stringElement)))
                    put("distribution", agent.distribution.toJson())
                }
            )
        })
    }

    private fun AgentDistribution.toJson(): JsonObject {
        return JsonObject(
            buildMap {
                npx?.let { put("npx", it.toJson()) }
                uvx?.let { put("uvx", it.toJson()) }
                if (binary.isNotEmpty()) {
                    put(
                        "binary",
                        JsonObject(binary.mapValues { (_, value) -> value.toJson() })
                    )
                }
            }
        )
    }

    private fun CommandDistribution.toJson(): JsonObject {
        return JsonObject(
            buildMap {
                put("package", stringElement(`package`))
                put("args", JsonArray(args.map(::stringElement)))
                put(
                    "env",
                    JsonObject(env.mapValues { (_, value) -> stringElement(value) })
                )
            }
        )
    }

    private fun BinaryDistribution.toJson(): JsonObject {
        return JsonObject(
            buildMap {
                put("archive", stringElement(archive))
                put("cmd", stringElement(cmd))
                put("args", JsonArray(args.map(::stringElement)))
                put(
                    "env",
                    JsonObject(env.mapValues { (_, value) -> stringElement(value) })
                )
            }
        )
    }

    private fun stringElement(value: String): JsonElement {
        return json.parseToJsonElement("\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    private fun JsonElement?.stringOrNull(): String? {
        return runCatching { this?.jsonPrimitive?.content }.getOrNull()
    }
}
