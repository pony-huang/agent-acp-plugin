package github.ponyhuang.acpplugin.services

import github.ponyhuang.acpplugin.settings.AcpPluginSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.io.path.createDirectories

class AcpAgentsConfigServiceTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            resetState()
        } finally {
            super.tearDown()
        }
    }

    fun testGetInstalledAgentsUsesInstalledSettingsAndRegistryMetadata() {
        val settings = AcpPluginSettings.getInstance()
        settings.installedAgents = mutableListOf(
            AcpPluginSettings.InstalledAgentSetting(
                registryAgentId = "codex-acp",
                displayName = "Codex CLI",
                installMethod = InstallMethod.NPX,
                command = "npx.cmd",
                args = listOf("-y", "@zed-industries/codex-acp@0.11.1"),
                env = emptyMap(),
                installedVersion = "0.11.1",
                sourceLabel = "Official ACP registry",
                description = "",
                isLegacy = false
            )
        )

        val registryService = ApplicationManager.getApplication().getService(AcpAgentRegistryService::class.java)
        val iconService = ApplicationManager.getApplication().getService(AcpAgentIconService::class.java)
        registryService.replaceSnapshotForTests(
            AcpAgentRegistryService.RegistrySnapshot(
                version = "1.0.0",
                refreshedAtMillis = 123L,
                agents = listOf(
                    AcpAgentRegistryService.RegistryAgent(
                        id = "codex-acp",
                        name = "Codex CLI",
                        version = "0.11.1",
                        description = "ACP adapter for OpenAI's coding assistant",
                        repository = "https://github.com/zed-industries/codex-acp",
                        website = null,
                        authors = listOf("OpenAI"),
                        license = "Apache-2.0",
                        icon = "https://cdn.agentclientprotocol.com/registry/v1/latest/codex-acp.svg",
                        distribution = AcpAgentRegistryService.AgentDistribution(
                            npx = AcpAgentRegistryService.CommandDistribution(
                                `package` = "@zed-industries/codex-acp@0.11.1"
                            )
                        )
                    )
                )
            )
        )
        val iconPath = requireNotNull(iconService.cachedIconPath("codex-acp", "https://cdn.agentclientprotocol.com/registry/v1/latest/codex-acp.svg"))
        iconPath.parent.createDirectories()
        Files.writeString(iconPath, """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"></svg>""")

        val configService = project.getService(AcpAgentsConfigService::class.java)
        val installed = configService.getInstalledAgents()

        assertSize(1, installed)
        assertEquals("codex-acp", installed.single().registryAgentId)
        assertEquals("ACP adapter for OpenAI's coding assistant", installed.single().description)
        assertEquals("0.11.1", installed.single().version)
        assertEquals(iconPath.toString(), installed.single().iconPath)
        assertEquals(InstallMethod.NPX, installed.single().installMethod)
    }

    fun testGetConfigIncludesOnlyInstalledAgents() {
        val settings = AcpPluginSettings.getInstance()
        settings.installedAgents = mutableListOf(
            AcpPluginSettings.InstalledAgentSetting(
                registryAgentId = "gemini",
                displayName = "Gemini CLI",
                installMethod = InstallMethod.NPX,
                command = "npx.cmd",
                args = listOf("-y", "@google/gemini-cli@0.39.0", "--acp"),
                env = mapOf("A" to "B"),
                installedVersion = "0.39.0",
                sourceLabel = "Official ACP registry",
                description = "Google's official CLI for Gemini",
                isLegacy = false
            )
        )

        val configService = project.getService(AcpAgentsConfigService::class.java)
        val config = configService.getConfig()

        assertEquals(listOf("Gemini CLI"), config.agents.keys.toList())
        val gemini = config.agents.getValue("Gemini CLI")
        assertEquals("npx.cmd", gemini.command)
        assertEquals(listOf("-y", "@google/gemini-cli@0.39.0", "--acp"), gemini.args)
        assertEquals(mapOf("A" to "B"), gemini.env)
    }

    private fun resetState() {
        AcpPluginSettings.getInstance().loadState(AcpPluginSettings())
        ApplicationManager.getApplication().getService(AcpAgentRegistryService::class.java).replaceSnapshotForTests(null)
    }
}
