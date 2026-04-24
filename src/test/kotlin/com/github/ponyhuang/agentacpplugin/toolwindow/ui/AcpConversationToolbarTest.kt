package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpConversationToolbarTest : BasePlatformTestCase() {

    @Suppress("DEPRECATION")
    fun testToolbarExposesSessionAction() {
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = {},
            onShowSessions = {},
            onCancel = {}
        )

        assertFalse(acpChatViewToolbar.isStopActionEnabled())
        assertNotNull(acpChatViewToolbar.toolbar.component)
        assertEquals(2, acpChatViewToolbar.actionGroup.childActionsOrStubs.size)
        assertTrue(acpChatViewToolbar.isNewSessionActionEnabled())
        assertTrue(acpChatViewToolbar.isSessionActionEnabled())
        assertTrue(acpChatViewToolbar.actionGroup.childActionsOrStubs.all { (it as AnAction).displayTextInToolbar() })
    }

    fun testNewSessionActionDisabledWithoutSelectedAgent() {
        var created = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { false },
            onNewSession = { created = true },
            onShowSessions = {},
            onCancel = {}
        )

        acpChatViewToolbar.performNewSessionAction()

        assertFalse(acpChatViewToolbar.isNewSessionActionEnabled())
        assertFalse(created)
    }

    fun testSessionActionDisabledWithoutSelectedAgent() {
        var opened = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { false },
            onNewSession = {},
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
            onNewSession = {},
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
            onNewSession = {},
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
            onNewSession = {},
            onShowSessions = {},
            onCancel = {}
        )

        acpChatViewToolbar.update()

        assertFalse(acpChatViewToolbar.isNewSessionActionEnabled())
        assertFalse(acpChatViewToolbar.isSessionActionEnabled())
        assertTrue(acpChatViewToolbar.isSessionLoadingIndicatorVisible())
    }

    fun testPerformNewSessionActionInvokesCallbackWhenEnabled() {
        var created = false
        val acpChatViewToolbar = AcpChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = { created = true },
            onShowSessions = {},
            onCancel = {}
        )

        acpChatViewToolbar.performNewSessionAction()

        assertTrue(created)
    }
}
