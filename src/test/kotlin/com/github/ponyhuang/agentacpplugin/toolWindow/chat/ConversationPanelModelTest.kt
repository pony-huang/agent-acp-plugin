package com.github.ponyhuang.agentacpplugin.toolWindow.chat

import com.github.ponyhuang.agentacpplugin.services.render.SessionHeaderState
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot
import com.github.ponyhuang.agentacpplugin.services.render.TimelineDisplayState
import com.github.ponyhuang.agentacpplugin.services.render.TimelineItem
import com.github.ponyhuang.agentacpplugin.services.render.TimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant
import kotlin.io.path.readText

class ConversationPanelModelTest {
    @Test
    fun testConversationPanelModelKeepsThoughtBeforeFinalMessage() {
        val fixture = Paths.get("src/test/testData/toolWindow/streaming-thought.json").readText()
        val model = ConversationPanelModel.from(
            SessionViewSnapshot(
                sessionId = "session-1",
                headerState = SessionHeaderState("Session", "CONNECTED", "STREAMING", usageSummary = fixture),
                visibleTimeline = listOf(
                    TimelineItem("1", "turn-1", TimelineItemType.THOUGHT, TimelineDisplayState.IN_PROGRESS, 0, title = "Thought", textContent = "thinking", createdAt = Instant.now(), updatedAt = Instant.now()),
                    TimelineItem("2", "turn-1", TimelineItemType.FINAL_MESSAGE, TimelineDisplayState.IN_PROGRESS, 1, title = "Assistant", textContent = "answer", createdAt = Instant.now(), updatedAt = Instant.now()),
                ),
                composerEnabled = true,
            ),
        )
        assertEquals("THOUGHT", model.timeline.first().type)
        assertTrue(model.composerEnabled)
    }
}
