package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpConversationToolbarTest : BasePlatformTestCase() {

    fun testToolbarExposesSessionAction() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            hasSelectedAgent = { true },
            onShowSessions = {},
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertNotNull(acpChatViewToolbar.toolbar.component)
        assertEquals(1, acpChatViewToolbar.actionGroup.childActionsOrStubs.size)
        assertTrue(acpChatViewToolbar.isSessionActionEnabled())
    }

    fun testSessionActionDisabledWithoutSelectedAgent() {
        var opened = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            hasSelectedAgent = { false },
            onShowSessions = { opened = true },
            onCancel = {}
        )

        acpChatViewToolbar.performSessionAction()

        assertFalse(acpChatViewToolbar.isSessionActionEnabled())
        assertFalse(opened)
    }

    fun testSessionActionDisabledWhileLoading() {
        var opened = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { true },
            hasSelectedAgent = { true },
            onShowSessions = { opened = true },
            onCancel = {}
        )

        acpChatViewToolbar.performSessionAction()

        assertFalse(acpChatViewToolbar.isSessionActionEnabled())
        assertFalse(opened)
    }

    fun testPerformSessionActionInvokesPopupCallbackWhenEnabled() {
        var opened = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            hasSelectedAgent = { true },
            onShowSessions = { opened = true },
            onCancel = {}
        )

        acpChatViewToolbar.performSessionAction()

        assertTrue(opened)
    }
}
