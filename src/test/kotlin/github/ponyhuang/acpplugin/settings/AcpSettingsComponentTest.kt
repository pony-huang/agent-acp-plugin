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

    fun testOfficialTabBuildsExpectedGroupsAndSearchResults() {
        val component = createComponent()
        component.setRegistryData(snapshot(), installedAgents())

        val initial = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertEquals(
            listOf("Updates Available", "Installed", "Available", "Legacy Imports"),
            initial.groups.map { it.title }
        )
        assertEquals(listOf("registry:alpha"), initial.groups[0].itemKeys)
        assertEquals(listOf("registry:beta"), initial.groups[1].itemKeys)
        assertEquals(listOf("registry:gamma"), initial.groups[2].itemKeys)
        assertEquals(listOf("legacy:Legacy Tool"), initial.groups[3].itemKeys)

        component.debugSetSearchQuery(AcpSettingsComponent.BrowserTabKind.OFFICIAL, "legacy command")
        val search = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertTrue(search.searchMode)
        assertEquals(listOf("legacy:Legacy Tool"), search.searchResults)

        component.debugSetSearchQuery(AcpSettingsComponent.BrowserTabKind.OFFICIAL, "")
        val reset = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertFalse(reset.searchMode)
        assertEquals(listOf("registry:alpha"), reset.groups[0].itemKeys)
    }

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

    fun testDetailTabsAndActionStatesMatchAgentType() {
        val component = createComponent()
        component.setRegistryData(snapshot(), installedAgents())

        component.debugSelectItem(AcpSettingsComponent.BrowserTabKind.OFFICIAL, "registry:alpha")
        val updateState = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertEquals("Alpha Agent", updateState.selectedDetail?.title)
        assertTrue(updateState.actions.upgradeEnabled)
        assertTrue(updateState.actions.uninstallEnabled)
        assertFalse(updateState.actions.installEnabled)
        assertTrue(updateState.actions.openLinkEnabled)

        component.debugSetDetailTab(AcpSettingsComponent.BrowserTabKind.OFFICIAL, 1)
        val updateDetails = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertEquals("Details", updateDetails.selectedDetail?.selectedSubTab)
        assertTrue(updateDetails.selectedDetail?.detailsLines?.any { it.contains("Repository: https://repo.example/alpha") } == true)

        component.debugSelectItem(AcpSettingsComponent.BrowserTabKind.OFFICIAL, "registry:gamma")
        val availableState = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertTrue(availableState.actions.installEnabled)
        assertFalse(availableState.actions.upgradeEnabled)
        assertFalse(availableState.actions.uninstallEnabled)

        component.debugSelectItem(AcpSettingsComponent.BrowserTabKind.OFFICIAL, "legacy:Legacy Tool")
        val legacyState = component.debugTabState(AcpSettingsComponent.BrowserTabKind.OFFICIAL)
        assertFalse(legacyState.actions.installEnabled)
        assertFalse(legacyState.actions.upgradeEnabled)
        assertTrue(legacyState.actions.uninstallEnabled)
        assertFalse(legacyState.actions.openLinkEnabled)
        assertTrue(legacyState.selectedDetail?.overviewLines?.any { it.contains("Legacy import: Yes") } == true)
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
