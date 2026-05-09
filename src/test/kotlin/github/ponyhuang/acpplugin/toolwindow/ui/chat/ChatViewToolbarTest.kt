package github.ponyhuang.acpplugin.toolwindow.ui.chat

import github.ponyhuang.acpplugin.toolwindow.ToolWindowComposerState
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JLabel
import javax.swing.JPanel

class ChatViewToolbarTest : BasePlatformTestCase() {

    fun testToolbarActionGroupDoesNotContainConnectionStatusAction() {
        val toolbar = ChatViewToolbar(
            project = project,
            isLoading = { false },
            onCancel = {}
        )

        val actionGroup = readField<DefaultActionGroup>(toolbar, "toolbarGroup")
        val actionTexts = actionGroup.childActionsOrStubs.map { it.templateText }

        assertEquals(listOf("New Session", "Sessions", "Settings"), actionTexts)
    }

    fun testConnectingStateShowsPassiveStatusPanel() {
        val toolbar = ChatViewToolbar(
            project = project,
            isLoading = { true },
            onCancel = {},
            getComposerState = { ToolWindowComposerState.CONNECTING }
        )

        toolbar.updateConnectionStatus()

        val statusPanel = readField<JPanel>(toolbar, "statusPanel")
        val statusIconLabel = readField<JLabel>(toolbar, "statusIconLabel")

        assertTrue(statusPanel.isVisible)
        assertNotNull(statusIconLabel.icon)
    }

    fun testSendingStateShowsPassiveStatusPanelOnlyWhenConnected() {
        val toolbar = ChatViewToolbar(
            project = project,
            isLoading = { true },
            onCancel = {},
            isSessionConnected = { true },
            getComposerState = { ToolWindowComposerState.SENDING }
        )

        toolbar.updateConnectionStatus()

        val statusPanel = readField<JPanel>(toolbar, "statusPanel")
        val statusIconLabel = readField<JLabel>(toolbar, "statusIconLabel")

        assertTrue(statusPanel.isVisible)
        assertNotNull(statusIconLabel.icon)
    }

    fun testIdleConnectedStateHidesPassiveStatusPanel() {
        val toolbar = ChatViewToolbar(
            project = project,
            isLoading = { false },
            onCancel = {},
            isSessionConnected = { true },
            getComposerState = { ToolWindowComposerState.IDLE }
        )

        toolbar.updateConnectionStatus()

        val statusPanel = readField<JPanel>(toolbar, "statusPanel")
        val statusIconLabel = readField<JLabel>(toolbar, "statusIconLabel")

        assertFalse(statusPanel.isVisible)
        assertNull(statusIconLabel.icon)
    }

    private inline fun <reified T> readField(target: Any, fieldName: String): T {
        return target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(target) as T
    }
}
