package com.github.ponyhuang.agentacpplugin.toolWindow.components

import com.github.ponyhuang.agentacpplugin.services.AcpProjectService
import com.github.ponyhuang.agentacpplugin.toolWindow.chat.ConversationPanelModel
import com.github.ponyhuang.agentacpplugin.toolWindow.chat.ConversationTimelinePanel
import com.github.ponyhuang.agentacpplugin.toolWindow.model.ConnectionStatusViewModel
import com.github.ponyhuang.agentacpplugin.toolWindow.model.SessionListViewModel
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

class AcpToolWindowPanel(project: Project) : JPanel(BorderLayout()) {
    private val service = project.service<AcpProjectService>()
    private val connectionPanel = AgentConnectionPanel(service::connect)
    private val headerPanel = ConversationHeaderPanel()
    private val timelinePanel = ConversationTimelinePanel()
    private val inputPanel = UserInputPanel(service::submitPrompt)
    private val sessionListPanel = SessionListPanel(service::selectSession)
    private val sessionMetaPanel = SessionMetaPanel()
    private val permissionPromptPanel = PermissionPromptPanel()

    init {
        border = JBUI.Borders.empty(8)
        add(connectionPanel, BorderLayout.NORTH)
        add(sessionListPanel, BorderLayout.WEST)
        add(sessionMetaPanel, BorderLayout.EAST)
        add(
            ConversationInputSplitterPanel(
                headerPanel = headerPanel,
                timelinePanel = timelinePanel,
                inputPanel = inputPanel,
            ),
            BorderLayout.CENTER,
        )
        add(permissionPromptPanel, BorderLayout.SOUTH)

        service.addSnapshotListener { snapshots ->
            SwingUtilities.invokeLater {
                val selectedSessionId = service.selectedSessionId() ?: snapshots.keys.firstOrNull()
                val sessionListViewModel = SessionListViewModel.fromSnapshots(snapshots, selectedSessionId)
                sessionListPanel.render(sessionListViewModel.items)
                sessionMetaPanel.render(sessionListViewModel)
                val selectedSnapshot = selectedSessionId?.let(snapshots::get)
                connectionPanel.render(
                    ConnectionStatusViewModel(
                        statusText = selectedSnapshot?.headerState?.connectionStatus ?: "DISCONNECTED",
                        canConnect = true,
                        errorText = selectedSnapshot?.bannerState?.takeIf { it.isError }?.text,
                    ),
                )
                if (selectedSnapshot != null) {
                    val conversation = ConversationPanelModel.from(selectedSnapshot)
                    headerPanel.render(conversation.header)
                    timelinePanel.render(conversation.timeline, conversation.bannerText)
                    inputPanel.setComposerEnabled(conversation.composerEnabled)
                } else {
                    timelinePanel.render(emptyList(), null)
                    inputPanel.setComposerEnabled(false)
                }
            }
        }
        service.addPermissionListener { request ->
            SwingUtilities.invokeLater { permissionPromptPanel.render(request) }
        }
    }
}
