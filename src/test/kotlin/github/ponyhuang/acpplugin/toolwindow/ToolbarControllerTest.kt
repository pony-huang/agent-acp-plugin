package github.ponyhuang.acpplugin.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.MutableStateFlow

class ToolbarControllerTest : BasePlatformTestCase() {

    fun testNewSessionActionDelegatesWhenEnabled() {
        var callCount = 0
        val controller = controller(
            hasSelectedAgent = { true },
            onNewSession = { callCount++ }
        )
        try {
            controller.toolbar.performNewSessionAction()

            assertEquals(1, callCount)
        } finally {
            controller.dispose()
        }
    }

    fun testNewSessionActionDoesNotDelegateWithoutSelectedAgent() {
        var callCount = 0
        val controller = controller(
            hasSelectedAgent = { false },
            onNewSession = { callCount++ }
        )
        try {
            controller.toolbar.performNewSessionAction()

            assertEquals(0, callCount)
        } finally {
            controller.dispose()
        }
    }

    fun testSessionActionDelegatesWhenEnabled() {
        var callCount = 0
        val controller = controller(
            hasSelectedAgent = { true },
            onShowSessions = { callCount++ }
        )
        try {
            controller.toolbar.performSessionAction()

            assertEquals(1, callCount)
        } finally {
            controller.dispose()
        }
    }

    fun testToolbarStateReflectsListingSessions() {
        val listingSessions = MutableStateFlow(true)
        val controller = controller(
            hasSelectedAgent = { true },
            listingSessions = listingSessions,
            isListingSessions = { listingSessions.value }
        )
        try {
            assertFalse(controller.toolbar.isNewSessionActionEnabled())
            assertFalse(controller.toolbar.isSessionActionEnabled())

            listingSessions.value = false

            assertTrue(controller.toolbar.isNewSessionActionEnabled())
            assertTrue(controller.toolbar.isSessionActionEnabled())
        } finally {
            controller.dispose()
        }
    }

    private fun controller(
        loading: MutableStateFlow<Boolean> = MutableStateFlow(false),
        connected: MutableStateFlow<Boolean> = MutableStateFlow(false),
        switching: MutableStateFlow<Boolean> = MutableStateFlow(false),
        listingSessions: MutableStateFlow<Boolean> = MutableStateFlow(false),
        isLoading: () -> Boolean = { loading.value || switching.value },
        isListingSessions: () -> Boolean = { listingSessions.value },
        hasSelectedAgent: () -> Boolean = { false },
        onNewSession: () -> Unit = {},
        onShowSessions: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) = ToolbarController(
        project = project,
        loading = loading,
        connected = connected,
        switching = switching,
        listingSessions = listingSessions,
        isLoading = isLoading,
        isListingSessions = isListingSessions,
        hasSelectedAgent = hasSelectedAgent,
        isSessionConnected = { connected.value },
        getComposerState = { ToolWindowComposerState.IDLE },
        onNewSession = onNewSession,
        onShowSessions = onShowSessions,
        onCancel = onCancel
    )
}
