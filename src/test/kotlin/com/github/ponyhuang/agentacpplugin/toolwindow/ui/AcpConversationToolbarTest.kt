package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpConversationToolbarTest : BasePlatformTestCase() {

    fun testStopActionDisabledWhenSessionIsIdle() {
        val acpConversationToolbar = AcpConversationToolbar(
            isLoading = { false },
            onCancel = {}
        )

        assertFalse(acpConversationToolbar.isStopActionEnabled())
        assertNotNull(acpConversationToolbar.toolbar.component)
    }

    fun testStopActionEnabledWhenSessionIsRunning() {
        val acpConversationToolbar = AcpConversationToolbar(
            isLoading = { true },
            onCancel = {}
        )

        assertTrue(acpConversationToolbar.isStopActionEnabled())
    }

    fun testStopActionInvokesCancelCallback() {
        var cancelled = false
        val acpConversationToolbar = AcpConversationToolbar(
            isLoading = { true },
            onCancel = { cancelled = true }
        )

        acpConversationToolbar.performStopAction()

        assertTrue(cancelled)
    }
}
