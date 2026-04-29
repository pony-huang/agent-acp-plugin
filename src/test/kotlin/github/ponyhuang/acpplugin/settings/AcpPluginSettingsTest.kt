package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpPluginSettingsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            resetSettings()
        } finally {
            super.tearDown()
        }
    }

    fun testLoadStateMigratesKnownLegacyAgentToInstalledAgent() {
        val settings = AcpPluginSettings.getInstance()
        val state = AcpPluginSettings().apply {
            agentSettings = mutableListOf(
                AcpPluginSettings.LegacyAgentSetting(
                    name = "GitHub Copilot",
                    command = "npx.cmd",
                    args = listOf("@github/copilot@1.0.34", "--acp"),
                    env = emptyMap()
                )
            )
        }

        settings.loadState(state)

        assertEmpty(settings.agentSettings)
        assertSize(1, settings.installedAgents)
        val installed = settings.installedAgents.single()
        assertEquals("github-copilot-cli", installed.registryAgentId)
        assertEquals("GitHub Copilot", installed.displayName)
        assertEquals(InstallMethod.NPX, installed.installMethod)
        assertFalse(installed.isLegacy)
    }

    fun testLoadStateKeepsUnknownLegacyAgentAsLegacyInstalledAgent() {
        val settings = AcpPluginSettings.getInstance()
        val state = AcpPluginSettings().apply {
            agentSettings = mutableListOf(
                AcpPluginSettings.LegacyAgentSetting(
                    name = "Internal Agent",
                    command = "internal-agent",
                    args = listOf("acp"),
                    env = mapOf("TOKEN" to "value")
                )
            )
        }

        settings.loadState(state)

        assertEmpty(settings.agentSettings)
        assertSize(1, settings.installedAgents)
        val installed = settings.installedAgents.single()
        assertTrue(installed.isLegacy)
        assertEquals("", installed.registryAgentId)
        assertEquals("Internal Agent", installed.displayName)
        assertEquals("internal-agent", installed.command)
        assertEquals(listOf("acp"), installed.args)
    }

    private fun resetSettings() {
        AcpPluginSettings.getInstance().loadState(AcpPluginSettings())
    }
}
