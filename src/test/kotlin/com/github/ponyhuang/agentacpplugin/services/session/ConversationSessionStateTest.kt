package com.github.ponyhuang.agentacpplugin.services.session

import com.github.ponyhuang.agentacpplugin.services.render.RenderIntent
import com.github.ponyhuang.agentacpplugin.services.render.TimelineDisplayState
import com.github.ponyhuang.agentacpplugin.services.render.TimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionStateTest {
    @Test
    fun testTurnStoreMaintainsSingleStreamingTurn() {
        val store = ConversationTurnStore()
        store.startTurn("session-1", "hello")
        store.apply(
            "session-1",
            listOf(
                RenderIntent.MarkTurnStreaming,
                RenderIntent.AppendTimeline(TimelineItemType.THOUGHT, "thinking", TimelineDisplayState.IN_PROGRESS),
            ),
        )
        assertEquals(1, store.turns("session-1").count { it.turnStatus == TurnStatus.STREAMING })
        assertTrue(store.timeline("session-1").any { it.itemType == TimelineItemType.THOUGHT })
    }
}
