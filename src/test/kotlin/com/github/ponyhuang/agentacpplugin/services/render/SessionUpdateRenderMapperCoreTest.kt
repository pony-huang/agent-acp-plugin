package com.github.ponyhuang.agentacpplugin.services.render

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUpdateRenderMapperCoreTest {
    private val mapper = SessionUpdateRenderMapper()

    @Test
    fun testCoreMessageAndSessionInfoUpdatesMapToExpectedIntents() {
        val messageIntents = mapper.map(SessionUpdate.AgentMessageChunk(ContentBlock.Text("final")))
        val thoughtIntents = mapper.map(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("thinking")))
        val userIntents = mapper.map(SessionUpdate.UserMessageChunk(ContentBlock.Text("hi")))
        val sessionInfoIntents = mapper.map(SessionUpdate.SessionInfoUpdate(title = "Renamed"))

        assertTrue(messageIntents.any { it is RenderIntent.AppendTimeline && it.itemType == TimelineItemType.FINAL_MESSAGE })
        assertTrue(thoughtIntents.any { it is RenderIntent.AppendTimeline && it.itemType == TimelineItemType.THOUGHT })
        assertTrue(userIntents.any { it is RenderIntent.AppendTimeline && it.itemType == TimelineItemType.USER_MESSAGE })
        assertEquals("Renamed", (sessionInfoIntents.single() as RenderIntent.UpdateSessionTitle).title)
    }
}
