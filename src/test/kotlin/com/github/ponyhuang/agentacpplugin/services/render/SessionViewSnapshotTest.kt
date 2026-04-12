package com.github.ponyhuang.agentacpplugin.services.render

import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointState
import com.github.ponyhuang.agentacpplugin.services.session.ConversationSessionState
import com.github.ponyhuang.agentacpplugin.services.session.SessionStatus
import com.github.ponyhuang.agentacpplugin.services.session.ToolCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionViewSnapshotTest {
    @Test
    fun testSnapshotPreservesTimelineOrderingAndComposerFlag() {
        val snapshot = SessionViewSnapshotStore().rebuild(
            endpoint = AgentEndpointState("endpoint-1", "agent", AgentConnectionStatus.CONNECTED),
            session = ConversationSessionState("session-1", "endpoint-1", sessionStatus = SessionStatus.IDLE),
            timeline = listOf(
                TimelineItem("item-1", "turn-1", TimelineItemType.THOUGHT, TimelineDisplayState.IN_PROGRESS, 0, textContent = "a", createdAt = Instant.now(), updatedAt = Instant.now()),
                TimelineItem("item-2", "turn-1", TimelineItemType.FINAL_MESSAGE, TimelineDisplayState.COMPLETED, 1, textContent = "b", createdAt = Instant.now(), updatedAt = Instant.now()),
            ),
            toolCalls = listOf(ToolCallState("tool-1", "turn-1", "shell")),
        )
        assertEquals(listOf("item-1", "item-2"), snapshot.visibleTimeline.map { it.itemId })
        assertTrue(snapshot.composerEnabled)
    }
}
