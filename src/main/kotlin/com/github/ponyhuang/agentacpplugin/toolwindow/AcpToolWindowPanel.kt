package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AcpProjectService
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpConversationPanel
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpUserInputPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.util.Disposer

class AcpToolWindowPanel(
    var project: Project,
    var disposable: Disposable
) : SimpleToolWindowPanel(true) {
    private val logger: Logger = Logger.getInstance(AcpToolWindowPanel::class.java)
    private val conversationPanel = AcpConversationPanel(project)
    private val userInputPanel = AcpUserInputPanel(project)
    private val controller = AcpToolWindowController(
        projectService = project.service<AcpProjectService>(),
        appendItem = conversationPanel::append,
        updateItem = conversationPanel::upsert,
        setComposerState = userInputPanel::setBusy,
    )
    private val conversationScrollPane: JBScrollPane = ScrollPaneFactory.createScrollPane(
        conversationPanel, true
    ) as JBScrollPane

    init {
        logger.info("AcpToolWindowPanel init")
        userInputPanel.onSubmit = { prompt -> controller.submitPrompt(prompt) }
        userInputPanel.onAgentChanged = { agent -> controller.selectAgent(agent) }
        userInputPanel.setBusy(ToolWindowComposerState.IDLE)
        Disposer.register(disposable, controller)
        val splitter = Splitter(
            true,   // vertical split
            0.8f    // 8:2 ratio
        ).apply {
            setFirstComponent(conversationScrollPane)
            setSecondComponent(userInputPanel)
        }
        splitter.setHonorComponentsMinimumSize(true)
        setContent(splitter)
    }
}
