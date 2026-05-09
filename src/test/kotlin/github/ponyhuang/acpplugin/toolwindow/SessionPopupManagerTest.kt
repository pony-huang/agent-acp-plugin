package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.services.InstallMethod
import java.awt.event.MouseEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPopupManagerTest {

    @Test
    fun createSessionListSelectsFirstSession() {
        val manager = manager()

        val list = manager.createSessionList(
            agent = agent(),
            cwd = "E:/project",
            sessions = listOf(session("session-1"), session("session-2"))
        )

        assertEquals(8, list.visibleRowCount)
        assertEquals(0, list.selectedIndex)
        assertEquals("session-1", list.selectedValue.sessionId)
        manager.dispose()
    }

    @Test
    fun createSessionListShowsEmptyTextWhenNoSessionsExist() {
        val manager = manager()

        val list = manager.createSessionList(
            agent = agent(),
            cwd = "E:/project",
            sessions = emptyList()
        )

        assertEquals("No sessions found", list.emptyText.text)
        assertEquals(-1, list.selectedIndex)
        manager.dispose()
    }

    @Test
    fun clickingSessionInvokesResumeCallback() {
        var resumedSessionId: String? = null
        val manager = manager { _, _, session ->
            resumedSessionId = session.sessionId
        }
        val list = manager.createSessionList(
            agent = agent(),
            cwd = "E:/project",
            sessions = listOf(session("session-1"))
        )

        list.dispatchEvent(
            MouseEvent(
                list,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false
            )
        )

        assertEquals("session-1", resumedSessionId)
        assertFalse(manager.isShowing)
        manager.dispose()
    }

    @Test
    fun shortSessionIdTruncatesLongIds() {
        val manager = manager()

        assertEquals("short", manager.shortSessionId("short"))
        assertEquals("12345678...", manager.shortSessionId("12345678901"))
        manager.dispose()
    }

    @Test
    fun buildSessionSubtitleUsesTimestampAndShortSessionId() {
        val manager = manager()

        val subtitle = manager.buildSessionSubtitle(
            session(
                sessionId = "12345678901",
                updatedAtMillis = 0
            )
        )

        assertTrue(subtitle.endsWith(" • 12345678..."))
        manager.dispose()
    }

    private fun manager(
        onResume: (
            AgentRegistry.InstalledAgent,
            String,
            AcpSessionService.SessionListItem
        ) -> Unit = { _, _, _ -> }
    ) = SessionPopupManager(
        anchorComponent = JPanel(),
        onResumeSession = onResume
    )

    private fun session(
        sessionId: String,
        updatedAtMillis: Long? = null
    ) = AcpSessionService.SessionListItem(
        sessionId = sessionId,
        title = "Session",
        cwd = "E:/project",
        updatedAtMillis = updatedAtMillis
    )

    private fun agent() = AgentRegistry.InstalledAgent(
        registryAgentId = "agent-a",
        id = "agent-a",
        displayName = "Agent A",
        description = "Description",
        version = "1.0.0",
        iconPath = null,
        installMethod = InstallMethod.NPX,
        sourceLabel = "Official",
        command = "npx",
        args = emptyList(),
        env = emptyMap(),
        isLegacy = false,
    )
}
