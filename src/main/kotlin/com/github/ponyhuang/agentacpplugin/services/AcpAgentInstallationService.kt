package com.github.ponyhuang.agentacpplugin.services

import com.github.ponyhuang.agentacpplugin.settings.AcpPluginSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.Decompressor
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name

@Service(Service.Level.APP)
class AcpAgentInstallationService {
    data class InstallResult(
        val installedAgent: AcpPluginSettings.InstalledAgentSetting,
        val installPath: Path?,
    )

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun installAgent(agent: AcpAgentRegistryService.RegistryAgent): InstallResult {
        val installMethod = agent.distribution.primaryInstallMethod()
            ?: throw IOException("Agent '${agent.name}' does not expose a supported installation method.")

        return when (installMethod) {
            InstallMethod.NPX -> {
                val distribution = agent.distribution.npx
                    ?: throw IOException("Agent '${agent.name}' is missing npx distribution metadata.")
                InstallResult(
                    installedAgent = AcpPluginSettings.InstalledAgentSetting(
                        registryAgentId = agent.id,
                        displayName = agent.name,
                        installMethod = InstallMethod.NPX,
                        command = commandFor(InstallMethod.NPX),
                        args = listOf("-y", distribution.`package`) + distribution.args,
                        env = distribution.env,
                        installedVersion = agent.version,
                        installRoot = "",
                        sourceLabel = "Official ACP registry",
                        description = agent.description,
                        isLegacy = false,
                    ),
                    installPath = null,
                )
            }

            InstallMethod.UVX -> {
                val distribution = agent.distribution.uvx
                    ?: throw IOException("Agent '${agent.name}' is missing uvx distribution metadata.")
                InstallResult(
                    installedAgent = AcpPluginSettings.InstalledAgentSetting(
                        registryAgentId = agent.id,
                        displayName = agent.name,
                        installMethod = InstallMethod.UVX,
                        command = commandFor(InstallMethod.UVX),
                        args = listOf(distribution.`package`) + distribution.args,
                        env = distribution.env,
                        installedVersion = agent.version,
                        installRoot = "",
                        sourceLabel = "Official ACP registry",
                        description = agent.description,
                        isLegacy = false,
                    ),
                    installPath = null,
                )
            }

            InstallMethod.BINARY -> installBinaryAgent(agent)
        }
    }

    fun uninstallAgent(installedAgent: AcpPluginSettings.InstalledAgentSetting) {
        if (installedAgent.installMethod == InstallMethod.BINARY && installedAgent.installRoot.isNotBlank()) {
            Path.of(installedAgent.installRoot).toFile().deleteRecursively()
        }
    }

    private fun installBinaryAgent(agent: AcpAgentRegistryService.RegistryAgent): InstallResult {
        val platformKey = currentPlatformKey()
        val distribution = agent.distribution.binary[platformKey]
            ?: throw IOException("Agent '${agent.name}' does not provide a binary for $platformKey.")

        val installRoot = installRoot(agent)
        if (installRoot.exists()) {
            installRoot.toFile().deleteRecursively()
        }
        installRoot.createDirectories()

        val archiveName = URI.create(distribution.archive).path.substringAfterLast('/')
        val archivePath = installRoot.resolve(archiveName)
        downloadToFile(distribution.archive, archivePath)
        extractArchive(archivePath, installRoot)
        archivePath.deleteIfExists()

        val resolvedCommand = resolveInstalledCommand(installRoot, distribution.cmd)

        return InstallResult(
            installedAgent = AcpPluginSettings.InstalledAgentSetting(
                registryAgentId = agent.id,
                displayName = agent.name,
                installMethod = InstallMethod.BINARY,
                command = resolvedCommand.toString(),
                args = distribution.args,
                env = distribution.env,
                installedVersion = agent.version,
                installRoot = installRoot.toString(),
                sourceLabel = "Official ACP registry",
                description = agent.description,
                isLegacy = false,
            ),
            installPath = installRoot,
        )
    }

    private fun commandFor(installMethod: InstallMethod): String {
        return when (installMethod) {
            InstallMethod.NPX -> if (SystemInfo.isWindows) "npx.cmd" else "npx"
            InstallMethod.UVX -> if (SystemInfo.isWindows) "uvx.cmd" else "uvx"
            InstallMethod.BINARY -> error("Binary installs resolve their own command path.")
        }
    }

    private fun currentPlatformKey(): String {
        val os = when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac -> "darwin"
            SystemInfo.isLinux -> "linux"
            else -> throw IOException("Unsupported operating system for ACP binary installation.")
        }
        val arch = when {
            SystemInfo.isAarch64 -> "aarch64"
            System.getProperty("os.arch").contains("64") -> "x86_64"
            else -> throw IOException("Unsupported CPU architecture for ACP binary installation.")
        }
        return "$os-$arch"
    }

    private fun installRoot(agent: AcpAgentRegistryService.RegistryAgent): Path {
        return Path.of(PathManager.getConfigPath(), "ACPChat", "agents", agent.id, agent.version)
    }

    private fun downloadToFile(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        target.parent?.createDirectories()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() !in 200..299) {
            throw IOException("Failed to download agent archive: HTTP ${response.statusCode()}")
        }
    }

    private fun extractArchive(archivePath: Path, installRoot: Path) {
        when {
            archivePath.name.endsWith(".zip") -> Decompressor.Zip(archivePath).withZipExtensions().extract(installRoot)
            archivePath.name.endsWith(".tar.gz") || archivePath.name.endsWith(".tgz") || archivePath.name.endsWith(".tar.bz2") -> {
                Decompressor.Tar(archivePath).extract(installRoot)
            }
            else -> throw IOException("Unsupported archive format: ${archivePath.fileName}")
        }
    }

    private fun resolveInstalledCommand(installRoot: Path, command: String): Path {
        val normalized = command.removePrefix("./").replace('\\', '/')
        val resolved = installRoot.resolve(normalized.replace('/', java.io.File.separatorChar)).normalize()
        if (!resolved.startsWith(installRoot.normalize())) {
            throw IOException("Resolved command path escapes the installation directory: $command")
        }
        if (!Files.exists(resolved)) {
            throw IOException("Installed command was not found after extraction: $resolved")
        }
        return resolved
    }
}
