package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpConversationToolbarTest : BasePlatformTestCase() {

    fun testStopActionDisabledWhenSessionIsIdle() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertNotNull(acpChatViewToolbar.toolbar.component)
    }

    fun testStopActionEnabledWhenSessionIsRunning() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { true },
            onCancel = {}
        )

        assertTrue(acpChatViewToolbar.isStopActionEnabled())
    }

    fun testStopActionInvokesCancelCallback() {
        var cancelled = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { true },
            onCancel = { cancelled = true }
        )

        acpChatViewToolbar.performStopAction()

        assertTrue(cancelled)
    }
}
