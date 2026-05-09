package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList

class AcpPluginSettingsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            resetSettings()
        } finally {
            super.tearDown()
        }
    }

    fun testLoadStateDiscardsLegacyAgentSettings() {
        val settings = AcpPluginSettings.getInstance()
        val state = AcpPluginSettings().apply {
            agentSettings = CopyOnWriteArrayList(
                listOf(
                    AcpPluginSettings.LegacyAgentSetting(
                        name = "GitHub Copilot",
                        command = "npx.cmd",
                        args = listOf("@github/copilot@1.0.34", "--acp"),
                        env = emptyMap()
                    )
                )
            )
        }

        settings.loadState(state)

        assertEmpty(settings.agentSettings)
        assertEmpty(settings.installedAgents)
    }

    fun testGetStateReturnsIndependentCopy() {
        val settings = AcpPluginSettings().apply {
            installedAgents = CopyOnWriteArrayList(
                listOf(
                    AcpPluginSettings.InstalledAgentSetting(
                        registryAgentId = "alpha",
                        displayName = "Alpha Agent",
                        installMethod = InstallMethod.NPX,
                        command = "npx.cmd",
                        args = listOf("-y", "@acp/alpha"),
                        env = mapOf("TOKEN" to "one"),
                        installedVersion = "1.0.0",
                        installRoot = "/tmp/alpha",
                        sourceLabel = "Official ACP registry",
                        description = "Original"
                    )
                )
            )
            agentSettings = CopyOnWriteArrayList(
                listOf(
                    AcpPluginSettings.LegacyAgentSetting(
                        name = "Legacy Tool",
                        command = "legacy-tool",
                        args = listOf("--serve"),
                        env = mapOf("LEGACY" to "yes")
                    )
                )
            )
            registryLastRefreshMillis = 1234L
        }

        val state = settings.getState()

        assertNotSame(settings, state)
        assertNotSame(settings.installedAgents, state.installedAgents)
        assertEquals(settings.registryLastRefreshMillis, state.registryLastRefreshMillis)
        assertEquals(settings.installedAgents, state.installedAgents)
        assertEmpty(state.agentSettings)

        state.installedAgents[0].displayName = "Changed Agent"
        state.installedAgents.add(AcpPluginSettings.InstalledAgentSetting(displayName = "New Agent"))
        state.agentSettings.add(AcpPluginSettings.LegacyAgentSetting(name = "Another Legacy"))
        state.registryLastRefreshMillis = 9999L

        assertEquals("Alpha Agent", settings.installedAgents.single().displayName)
        assertEquals(1, settings.installedAgents.size)
        assertEquals("Legacy Tool", settings.agentSettings.single().name)
        assertEquals(1, settings.agentSettings.size)
        assertEquals(1, state.agentSettings.size)
        assertEquals(1234L, settings.registryLastRefreshMillis)
    }

    private fun resetSettings() {
        AcpPluginSettings.getInstance().loadState(AcpPluginSettings())
    }
}
