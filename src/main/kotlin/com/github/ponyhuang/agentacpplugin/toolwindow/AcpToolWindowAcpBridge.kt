package com.github.ponyhuang.agentacpplugin.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager

class AcpToolWindowAcpBridge(
    private val setComposerState: (ToolWindowComposerState) -> Unit,
    private val uiExecutor: (((() -> Unit))) -> Unit = { action ->
        ApplicationManager.getApplication().invokeLater(action)
    },
) : Disposable {
    override fun dispose() {
        TODO("Not yet implemented")
    }

}
