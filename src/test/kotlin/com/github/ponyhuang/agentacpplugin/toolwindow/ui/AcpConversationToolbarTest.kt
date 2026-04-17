package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpConversationToolbarTest : BasePlatformTestCase() {

    fun testToolbarStartsEmptyWhenSessionIsIdle() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertNotNull(acpChatViewToolbar.toolbar.component)
        assertEquals(0, acpChatViewToolbar.actionGroup.childActionsOrStubs.size)
    }

    fun testToolbarRemainsEmptyWhenSessionIsRunning() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { true },
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertEquals(0, acpChatViewToolbar.actionGroup.childActionsOrStubs.size)
    }

    fun testPerformStopActionDoesNothingWhenToolbarIsReserved() {
        var cancelled = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { true },
            onCancel = { cancelled = true }
        )

        acpChatViewToolbar.performStopAction()

        assertFalse(cancelled)
    }

    fun testToolbarStaysEmptyForConnectionOnlyLoadingState() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertEquals(0, acpChatViewToolbar.actionGroup.childActionsOrStubs.size)
    }
}
