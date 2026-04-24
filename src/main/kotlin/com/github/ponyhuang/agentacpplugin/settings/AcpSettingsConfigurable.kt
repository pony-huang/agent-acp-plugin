package com.github.ponyhuang.agentacpplugin.settings

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.services.AcpAgentInstallationService
import com.github.ponyhuang.agentacpplugin.services.AcpAgentRegistryService
import com.github.ponyhuang.agentacpplugin.services.AcpAgentsConfigService
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import org.jetbrains.annotations.Nls
import javax.swing.JComponent

class AcpSettingsConfigurable : Configurable {
    private val settings get() = AcpPluginSettings.getInstance()
    private val registryService: AcpAgentRegistryService
        get() = ApplicationManager.getApplication().getService(AcpAgentRegistryService::class.java)
    private val installationService: AcpAgentInstallationService
        get() = ApplicationManager.getApplication().getService(AcpAgentInstallationService::class.java)

    private var settingsComponent: AcpSettingsComponent? = null

    @Nls(capitalization = Nls.Capitalization.Title)
    override fun getDisplayName(): String = "ACP Chat"

    override fun getPreferredFocusedComponent(): JComponent? {
        return settingsComponent?.getPreferredFocusedComponent()
    }

    override fun createComponent(): JComponent {
        val component = AcpSettingsComponent(
            onRefreshRegistry = { refreshRegistry(forceRefresh = true) },
            onInstallAgent = { registryAgent, installedAgent, forceReinstall ->
                installRegistryAgent(registryAgent, installedAgent, forceReinstall)
            },
            onUninstallAgent = { installedAgent ->
                uninstallInstalledAgent(installedAgent)
            },
            onOpenLink = { url ->
                BrowserUtil.browse(url)
            }
        )
        settingsComponent = component
        component.setGeneralSettings(
            autoConnectEnabled = settings.autoConnectEnabled,
            showStartupNotifications = settings.showStartupNotifications,
            sessionsStoragePath = settings.sessionsStoragePath,
            effectiveSessionsPath = settings.getEffectiveSessionsPath(),
        )
        component.setRegistryData(
            snapshot = registryService.getCachedSnapshotOrNull()
                ?: runCatching { registryService.getSnapshot() }.getOrNull(),
            installedAgents = settings.installedAgents
        )
        return component.getPanel()
    }

    override fun isModified(): Boolean {
        val component = settingsComponent ?: return false
        return component.isGeneralSettingsModified(
            autoConnectEnabled = settings.autoConnectEnabled,
            showStartupNotifications = settings.showStartupNotifications,
            sessionsStoragePath = settings.sessionsStoragePath,
        )
    }

    override fun apply() {
        val component = settingsComponent ?: return
        val generalSettings = component.getGeneralSettings()
        settings.autoConnectEnabled = generalSettings.autoConnectEnabled
        settings.showStartupNotifications = generalSettings.showStartupNotifications
        settings.sessionsStoragePath = generalSettings.sessionsStoragePath
        notifyProjectsConfigChanged()
    }

    override fun reset() {
        val component = settingsComponent ?: return
        component.setGeneralSettings(
            autoConnectEnabled = settings.autoConnectEnabled,
            showStartupNotifications = settings.showStartupNotifications,
            sessionsStoragePath = settings.sessionsStoragePath,
            effectiveSessionsPath = settings.getEffectiveSessionsPath(),
        )
        component.setRegistryData(
            snapshot = registryService.getCachedSnapshotOrNull(),
            installedAgents = settings.installedAgents
        )
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }

    private fun refreshRegistry(forceRefresh: Boolean) {
        val snapshotHolder = arrayOfNulls<AcpAgentRegistryService.RegistrySnapshot>(1)
        val failureHolder = arrayOfNulls<Throwable>(1)
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    snapshotHolder[0] = registryService.getSnapshot(forceRefresh = forceRefresh)
                } catch (t: Throwable) {
                    failureHolder[0] = t
                }
            },
            if (forceRefresh) "Refreshing ACP registry" else "Loading ACP registry",
            true,
            null
        )
        failureHolder[0]?.let { error ->
            showError("Failed to load ACP registry: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        settingsComponent?.setRegistryData(snapshotHolder[0], settings.installedAgents)
    }

    private fun installRegistryAgent(
        registryAgent: AcpAgentRegistryService.RegistryAgent,
        installedAgent: AcpPluginSettings.InstalledAgentSetting?,
        forceReinstall: Boolean,
    ) {
        if (installedAgent != null && !forceReinstall) {
            return
        }
        val resultHolder = arrayOfNulls<AcpAgentInstallationService.InstallResult>(1)
        val failureHolder = arrayOfNulls<Throwable>(1)
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    installedAgent?.let { installationService.uninstallAgent(it) }
                    resultHolder[0] = installationService.installAgent(registryAgent)
                } catch (t: Throwable) {
                    failureHolder[0] = t
                }
            },
            if (installedAgent == null) "Installing ${registryAgent.name}" else "Updating ${registryAgent.name}",
            true,
            null
        )
        failureHolder[0]?.let { error ->
            showError("Failed to install ${registryAgent.name}: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        resultHolder[0]?.let { result ->
            settings.saveInstalledAgent(result.installedAgent)
            notifyProjectsConfigChanged()
            settingsComponent?.setRegistryData(
                snapshot = registryService.getCachedSnapshotOrNull(),
                installedAgents = settings.installedAgents
            )
        }
    }

    private fun uninstallInstalledAgent(installedAgent: AcpPluginSettings.InstalledAgentSetting) {
        installationService.uninstallAgent(installedAgent)
        if (installedAgent.registryAgentId.isNotBlank()) {
            settings.removeInstalledAgentByRegistryId(installedAgent.registryAgentId)
        } else {
            settings.removeInstalledAgent(installedAgent.displayName)
        }
        notifyProjectsConfigChanged()
        settingsComponent?.setRegistryData(
            snapshot = registryService.getCachedSnapshotOrNull(),
            installedAgents = settings.installedAgents
        )
    }

    private fun notifyProjectsConfigChanged() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            project.service<AcpAgentsConfigService>().notifyInstalledAgentsChanged()
        }
    }

    private fun showError(message: String) {
        Messages.showErrorDialog(message, MyBundle.message("plugin.settings.name"))
    }
}
