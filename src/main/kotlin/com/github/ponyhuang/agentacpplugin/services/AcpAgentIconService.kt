package com.github.ponyhuang.agentacpplugin.services

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.IconUtil
import com.intellij.util.ImageLoader
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

@Service(Service.Level.APP)
class AcpAgentIconService {
    private val logger = Logger.getInstance(AcpAgentIconService::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val iconCache = ConcurrentHashMap<String, Icon>()

    fun prefetchIcons(snapshot: AcpAgentRegistryService.RegistrySnapshot) {
        snapshot.agents.forEach { agent ->
            runCatching { downloadIcon(agent.id, agent.icon) }
                .onFailure { error ->
                    logger.warn("Failed to prefetch icon for agent ${agent.id}", error)
                }
        }
    }

    fun prefetchIcon(agent: AcpAgentRegistryService.RegistryAgent) {
        runCatching { downloadIcon(agent.id, agent.icon) }
            .onFailure { error ->
                logger.warn("Failed to prefetch icon for agent ${agent.id}", error)
            }
    }

    fun loadIcon(iconPath: String?, fallback: Icon = fallbackIcon(), size: Int = 16): Icon {
        if (iconPath.isNullOrBlank()) {
            return IconUtil.resizeSquared(fallback, size)
        }
        val cacheKey = "$iconPath#$size"
        return iconCache.computeIfAbsent(cacheKey) {
            val image = runCatching { ImageLoader.loadCustomIcon(Path.of(iconPath).toFile()) }
                .onFailure { error -> logger.warn("Failed to load cached icon from $iconPath", error) }
                .getOrNull()
            image?.let { loaded -> IconUtil.resizeSquared(IconUtil.createImageIcon(loaded), size) }
                ?: IconUtil.resizeSquared(fallback, size)
        }
    }

    fun fallbackIcon(): Icon = AllIcons.Nodes.Plugin

    fun cachedIconPath(agentId: String, iconUrl: String?): Path? {
        if (agentId.isBlank() || iconUrl.isNullOrBlank()) {
            return null
        }
        val extension = runCatching {
            val fileName = Path.of(URI.create(iconUrl).path).fileName?.toString().orEmpty()
            fileName.substringAfterLast('.', "svg").takeIf { it.isNotBlank() } ?: "svg"
        }.getOrDefault("svg")
        return iconsRoot().resolve("$agentId.$extension")
    }

    fun resolveCachedIconPath(agentId: String, iconUrl: String?): String? {
        val path = cachedIconPath(agentId, iconUrl) ?: return null
        return path.takeIf { it.exists() }?.toString()
    }

    private fun downloadIcon(agentId: String, iconUrl: String?) {
        val target = cachedIconPath(agentId, iconUrl) ?: return
        val request = HttpRequest.newBuilder(URI.create(iconUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "image/svg+xml,image/png,image/*")
            .GET()
            .build()
        target.parent?.createDirectories()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() !in 200..299) {
            target.deleteIfExists()
            throw IOException("Failed to download agent icon: HTTP ${response.statusCode()}")
        }
        iconCache.keys.removeIf { it.startsWith("${target}#") }
    }

    private fun iconsRoot(): Path {
        return Path.of(PathManager.getConfigPath(), "ACPChat", "registry", "icons")
    }
}
