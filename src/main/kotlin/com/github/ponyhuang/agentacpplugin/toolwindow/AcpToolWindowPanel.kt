package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpConversationPanel
import com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpUserInputPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter

class AcpToolWindowPanel(
    var project: Project,
    var disposable: Disposable
) : SimpleToolWindowPanel(true) {
    private val logger: Logger = Logger.getInstance(AcpToolWindowPanel::class.java)

    init {
        logger.info("AcpToolWindowPanel init")
        val splitter = Splitter(
            true,   // vertical split
            0.8f    // 8:2 ratio
        ).apply {
            setFirstComponent(AcpConversationPanel(project))
            setSecondComponent(AcpUserInputPanel(project))
        }
        splitter.setHonorComponentsMinimumSize(true)
        setContent(splitter)
    }
}
