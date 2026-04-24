package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpConversationToolbarTest : BasePlatformTestCase() {

    fun testToolbarExposesSessionAction() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onShowSessions = {},
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertNotNull(acpChatViewToolbar.toolbar.component)
        assertEquals(1, acpChatViewToolbar.actionGroup.childActionsOrStubs.size)
        assertTrue(acpChatViewToolbar.isSessionActionEnabled())
        assertTrue((acpChatViewToolbar.actionGroup.childActionsOrStubs.single() as AnAction).displayTextInToolbar())
    }

    fun testSessionActionDisabledWithoutSelectedAgent() {
        var opened = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
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
            isListingSessions = { false },
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
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onShowSessions = { opened = true },
            onCancel = {}
        )

        acpChatViewToolbar.performSessionAction()

        assertTrue(opened)
    }

    fun testSessionActionShowsLoadingIndicatorWhileListingSessions() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { true },
            hasSelectedAgent = { true },
            onShowSessions = {},
            onCancel = {}
        )

        acpChatViewToolbar.update()

        assertFalse(acpChatViewToolbar.isSessionActionEnabled())
        assertTrue(acpChatViewToolbar.isSessionLoadingIndicatorVisible())
    }
}
