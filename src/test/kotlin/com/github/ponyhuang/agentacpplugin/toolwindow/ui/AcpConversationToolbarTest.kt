package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowComposerState
import com.intellij.ui.AnimatedIcon
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel

class AcpConversationToolbarTest : BasePlatformTestCase() {

    @Suppress("DEPRECATION")
    fun testToolbarExposesSessionAction() {
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = {},
            onShowSessions = {},
            onCancel = {}
        )

        assertFalse(chatViewToolbar.isStopActionEnabled())
        assertNotNull(chatViewToolbar.toolbar.component)
        assertEquals(2, chatViewToolbar.actionGroup.childActionsOrStubs.size)
        assertTrue(chatViewToolbar.isNewSessionActionEnabled())
        assertTrue(chatViewToolbar.isSessionActionEnabled())
        assertTrue(chatViewToolbar.actionGroup.childActionsOrStubs.all { (it as AnAction).displayTextInToolbar() })
    }

    fun testNewSessionActionDisabledWithoutSelectedAgent() {
        var created = false
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { false },
            onNewSession = { created = true },
            onShowSessions = {},
            onCancel = {}
        )

        chatViewToolbar.performNewSessionAction()

        assertFalse(chatViewToolbar.isNewSessionActionEnabled())
        assertFalse(created)
    }

    fun testSessionActionDisabledWithoutSelectedAgent() {
        var opened = false
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { false },
            onNewSession = {},
            onShowSessions = { opened = true },
            onCancel = {}
        )

        chatViewToolbar.performSessionAction()

        assertFalse(chatViewToolbar.isSessionActionEnabled())
        assertFalse(opened)
    }

    fun testSessionActionDisabledWhileLoading() {
        var opened = false
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { true },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = {},
            onShowSessions = { opened = true },
            onCancel = {}
        )

        chatViewToolbar.performSessionAction()

        assertFalse(chatViewToolbar.isSessionActionEnabled())
        assertFalse(opened)
    }

    fun testPerformSessionActionInvokesPopupCallbackWhenEnabled() {
        var opened = false
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = {},
            onShowSessions = { opened = true },
            onCancel = {}
        )

        chatViewToolbar.performSessionAction()

        assertTrue(opened)
    }

    fun testSessionActionShowsLoadingIndicatorWhileListingSessions() {
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { false },
            isListingSessions = { true },
            hasSelectedAgent = { true },
            onNewSession = {},
            onShowSessions = {},
            onCancel = {}
        )

        chatViewToolbar.update()

        assertFalse(chatViewToolbar.isNewSessionActionEnabled())
        assertFalse(chatViewToolbar.isSessionActionEnabled())
        assertTrue(chatViewToolbar.isSessionLoadingIndicatorVisible())
    }

    fun testPerformNewSessionActionInvokesCallbackWhenEnabled() {
        var created = false
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = { created = true },
            onShowSessions = {},
            onCancel = {}
        )

        chatViewToolbar.performNewSessionAction()

        assertTrue(created)
    }

    fun testConnectionStatusIndicatorAnimatesWhileConnecting() {
        val chatViewToolbar = ChatViewToolbar(
            isLoading = { true },
            isListingSessions = { false },
            hasSelectedAgent = { true },
            onNewSession = {},
            onShowSessions = {},
            onCancel = {},
            isSessionConnected = { true },
            getComposerState = { ToolWindowComposerState.CONNECTING }
        )

        chatViewToolbar.updateConnectionStatus()

        val indicator = readPrivateField<JLabel>(chatViewToolbar, "connectionStatusIndicator")
        assertTrue(indicator.isVisible)
        assertSame(AnimatedIcon.Default.INSTANCE, indicator.icon)
    }

    private inline fun <reified T> readPrivateField(target: Any, fieldName: String): T {
        return target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(target) as T
    }
}
