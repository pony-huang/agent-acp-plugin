package com.github.ponyhuang.agentacpplugin.services.session

import com.github.ponyhuang.agentacpplugin.services.render.RenderIntent
import com.github.ponyhuang.agentacpplugin.services.render.TimelineDisplayState
import com.github.ponyhuang.agentacpplugin.services.render.TimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiSessionIsolationTest {
    @Test
    fun testDifferentSessionsKeepIndependentTimelineItems() {
        val store = ConversationTurnStore()
        store.startTurn("session-a", "a")
        store.startTurn("session-b", "b")
        store.apply("session-a", RenderIntent.AppendTimeline(TimelineItemType.FINAL_MESSAGE, "one", TimelineDisplayState.IN_PROGRESS))
        store.apply("session-b", RenderIntent.AppendTimeline(TimelineItemType.FINAL_MESSAGE, "two", TimelineDisplayState.IN_PROGRESS))

        assertEquals("one", store.timeline("session-a").last().textContent)
        assertEquals("two", store.timeline("session-b").last().textContent)
        assertTrue(store.timeline("session-a").none { it.textContent == "two" })
    }
}
