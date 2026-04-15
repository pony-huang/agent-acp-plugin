package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AgentNotifier
import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.github.ponyhuang.agentacpplugin.toolwindow.action.AgentComboBoxAction
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpConversationPanel
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpUserInputPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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

    private val configService = project.service<com.github.ponyhuang.agentacpplugin.services.AcpAgentsConfigService>()

    // Create agent selection notifier for linkage
    private val agentNotifier = AgentNotifier()

    // Get available agents from config
    private val availableAgents = AgentRegistry.getAvailableAgents(configService)

    // Create AgentItem list
    private val agentItems = availableAgents.map { agent ->
        AgentComboBoxAction.AgentItem(
            id = agent.id,
            displayName = agent.displayName,
            description = agent.description,
            agentDefinition = agent
        )
    }

    private val conversationPanel = AcpConversationPanel(project)

    // Initialize userInputPanel with linkage mechanism (callbacks set in init block)
    private val userInputPanel = AcpUserInputPanel(
        project = project,
        agentItems = agentItems,
        agentNotifier = agentNotifier,
        onModelChanged = { model -> logger.info("Model changed: ${model.id}") },
        onPlanChanged = { plan -> logger.info("Plan changed: ${plan.id}") }
    )

    private val controller = AcpBridge(
        setComposerState = userInputPanel::setBusy,
    )

    private val conversationScrollPane: JBScrollPane = ScrollPaneFactory.createScrollPane(
        conversationPanel, true
    ) as JBScrollPane

    init {
        logger.info("AcpToolWindowPanel init")
        // Set submit callback - ACP protocol implementation is deferred
        userInputPanel.onSubmit = { prompt ->
            logger.info("Submit prompt: $prompt (ACP bridge implementation deferred)")
        }
        userInputPanel.onAgentChanged = { agent ->
            logger.info("Agent changed: ${agent.displayName} (ACP bridge implementation deferred)")
        }
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
