package com.github.ponyhuang.agentacpplugin.services.render

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUpdateRenderMapperTest {
    private val mapper = SessionUpdateRenderMapper()

    @Test
    fun testUnknownUpdateFallsBackToStatusBanner() {
        val intents = mapper.map(
            SessionUpdate.UnknownSessionUpdate(
                sessionUpdateType = "future_update",
                rawJson = kotlinx.serialization.json.buildJsonObject {},
            ),
        )
        assertTrue(intents.any { it is RenderIntent.SetBanner })
        assertTrue(intents.any { it is RenderIntent.AppendTimeline && it.itemType == TimelineItemType.STATUS })
    }

    @Test
    fun testContentRendererHandlesTextBlocks() {
        assertEquals("hello", ContentBlockRenderer().render(ContentBlock.Text("hello")))
    }
}
