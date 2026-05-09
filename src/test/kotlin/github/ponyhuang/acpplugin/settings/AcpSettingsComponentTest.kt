package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.services.AcpAgentRegistryService
import github.ponyhuang.acpplugin.services.InstallMethod
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import java.time.Instant
import javax.swing.JComponent

class AcpSettingsComponentTest : BasePlatformTestCase() {

    fun testOfficialTabResolvesAgentIcons() {
        val component = createComponent()
        component.setRegistryData(snapshot(), installedAgents())

        assertTrue(component.debugHasResolvedIcon("registry:alpha"))
    }

    fun testInstalledStableAgentUsesUninstallAsPrimaryAction() {
        val component = createComponent()
        component.setRegistryData(snapshot(), installedAgents())

        assertEquals("Uninstall", component.debugPrimaryActionText("registry:beta"))
        assertNull(component.debugPrimaryStatusText("registry:gamma"))
    }

    fun testOfficialTabUsesResizableSplitterAndJetBrainsLists() {
        val component = createComponent()
        component.setRegistryData(snapshot(), installedAgents())

        val splitter = findDescendants(component.getPanel())
            .filterIsInstance<OnePixelSplitter>()
            .first { it.name == "agentBrowserSplitter" }
        val lists = findDescendants(component.getPanel())
            .filterIsInstance<JBList<*>>()
            .toList()

        assertFalse(splitter.isHonorMinimumSize)
        assertTrue(splitter.dividerWidth >= 3)
        assertTrue(splitter.minimumSize.width < 860)
        assertTrue(splitter.minimumSize.height < 500)
        assertTrue(lists.isNotEmpty())
        assertTrue(lists.any { it.name == "agentSearchResultsList" })
    }

    fun testSettingsPageUsesFullContentLayoutWithoutTopLevelOfficialTab() {
        val component = createComponent()
        component.setRegistryData(snapshot(), installedAgents())

        val tabbedPanes = findDescendants(component.getPanel())
            .filterIsInstance<JBTabbedPane>()
            .toList()

        assertFalse(tabbedPanes.any { pane ->
            (0 until pane.tabCount).any { pane.getTitleAt(it) == "Official" }
        })
    }

    private fun createComponent(): AcpSettingsComponent {
        return AcpSettingsComponent(
            onRefreshRegistry = {},
            onInstallAgent = { _, _, _ -> },
            onUninstallAgent = {},
            onOpenLink = {}
        )
    }

    private fun snapshot(): AcpAgentRegistryService.RegistrySnapshot {
        return AcpAgentRegistryService.RegistrySnapshot(
            version = "1",
            refreshedAtMillis = Instant.parse("2026-05-01T08:00:00Z").toEpochMilli(),
            agents = listOf(
                registryAgent(
                    id = "alpha",
                    name = "Alpha Agent",
                    version = "1.1.0",
                    description = "Needs update",
                    repository = "https://repo.example/alpha",
                    website = "https://alpha.example",
                    authors = listOf("ACP"),
                    license = "MIT",
                    distribution = AcpAgentRegistryService.AgentDistribution(
                        npx = AcpAgentRegistryService.CommandDistribution("@acp/alpha")
                    )
                ),
                registryAgent(
                    id = "beta",
                    name = "Beta Agent",
                    version = "2.0.0",
                    description = "Stable installed",
                    repository = "https://repo.example/beta",
                    website = null,
                    authors = listOf("ACP"),
                    license = "Apache-2.0",
                    distribution = AcpAgentRegistryService.AgentDistribution(
                        uvx = AcpAgentRegistryService.CommandDistribution("beta-agent")
                    )
                ),
                registryAgent(
                    id = "gamma",
                    name = "Gamma Agent",
                    version = "3.0.0",
                    description = "Fresh install candidate",
                    repository = null,
                    website = "https://gamma.example",
                    authors = listOf("ACP"),
                    license = null,
                    distribution = AcpAgentRegistryService.AgentDistribution(
                        binary = mapOf(
                            "windows-x86_64" to AcpAgentRegistryService.BinaryDistribution(
                                archive = "https://download.example/gamma.zip",
                                cmd = "gamma.exe"
                            )
                        )
                    )
                )
            )
        )
    }

    private fun installedAgents(): List<AcpPluginSettings.InstalledAgentSetting> {
        return listOf(
            AcpPluginSettings.InstalledAgentSetting(
                registryAgentId = "alpha",
                displayName = "Alpha Agent",
                installMethod = InstallMethod.NPX,
                command = "npx.cmd",
                args = listOf("-y", "@acp/alpha"),
                installedVersion = "1.0.0",
                sourceLabel = "Official ACP registry",
                description = "Needs update"
            ),
            AcpPluginSettings.InstalledAgentSetting(
                registryAgentId = "beta",
                displayName = "Beta Agent",
                installMethod = InstallMethod.UVX,
                command = "uvx.cmd",
                args = listOf("beta-agent"),
                installedVersion = "2.0.0",
                sourceLabel = "Official ACP registry",
                description = "Stable installed"
            ),
            AcpPluginSettings.InstalledAgentSetting(
                registryAgentId = "",
                displayName = "Legacy Tool",
                installMethod = InstallMethod.BINARY,
                command = "legacy command",
                args = listOf("--serve"),
                installedVersion = "",
                sourceLabel = "Legacy manual configuration",
                description = "Legacy imported entry",
                isLegacy = true
            )
        )
    }

    private fun registryAgent(
        id: String,
        name: String,
        version: String,
        description: String,
        repository: String?,
        website: String?,
        authors: List<String>,
        license: String?,
        distribution: AcpAgentRegistryService.AgentDistribution,
    ): AcpAgentRegistryService.RegistryAgent {
        return AcpAgentRegistryService.RegistryAgent(
            id = id,
            name = name,
            version = version,
            description = description,
            repository = repository,
            website = website,
            authors = authors,
            license = license,
            icon = null,
            distribution = distribution
        )
    }

    private fun findDescendants(component: JComponent): Sequence<java.awt.Component> = sequence {
        yield(component)
        component.components.forEach { child ->
            yield(child)
            if (child is JComponent) {
                yieldAll(findDescendants(child))
            }
        }
    }
}
