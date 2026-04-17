package com.github.ponyhuang.agentacpplugin.toolwindow

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpToolWindowPanelTest : BasePlatformTestCase() {

    fun testToolWindowPanelInstallsToolbarComponent() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)

            assertNotNull(panel.toolbar)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
