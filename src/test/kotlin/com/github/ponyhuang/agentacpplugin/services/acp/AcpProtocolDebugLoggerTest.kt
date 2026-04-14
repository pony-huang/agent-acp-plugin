package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class AcpProtocolDebugLoggerTest {
    @Test
    fun testSessionUpdateSummaryIncludesChunkPreview() {
        val summary = AcpProtocolDebugLogger.sessionUpdateSummary(
            SessionUpdate.AgentMessageChunk(ContentBlock.Text("line one\nline two")),
        )

        assertTrue(summary.contains("messageId=null"))
        assertTrue(summary.contains("preview=\"line one line two\""))
    }

    @Test
    fun testSessionUpdateSummaryIncludesToolCallFields() {
        val summary = AcpProtocolDebugLogger.sessionUpdateSummary(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-1"),
                title = "Write File",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(ToolCallContent.Content(ContentBlock.Text("writing file"))),
            ),
        )

        assertTrue(summary.contains("toolCallId=tool-1"))
        assertTrue(summary.contains("title=\"Write File\""))
        assertTrue(summary.contains("status=IN_PROGRESS"))
        assertTrue(summary.contains("kind=EDIT"))
        assertTrue(summary.contains("content=[content:writing file]"))
    }

    @Test
    fun testSessionUpdateSummaryIncludesUsageCost() {
        val summary = AcpProtocolDebugLogger.sessionUpdateSummary(
            SessionUpdate.UsageUpdate(
                used = 12,
                size = 34,
                cost = Cost(amount = 0.56, currency = "USD"),
            ),
        )

        assertEquals("used=12 size=34 cost=0.56 USD", summary)
    }

    @Test
    fun testSessionUpdateSummaryHandlesUnknownUpdateType() {
        val summary = AcpProtocolDebugLogger.sessionUpdateSummary(
            SessionUpdate.UnknownSessionUpdate(
                sessionUpdateType = "future_update",
                rawJson = buildJsonObject {
                    put("sessionUpdate", "future_update")
                    put("value", "42")
                },
            ),
        )

        assertEquals("sessionUpdateType=future_update", summary)
    }

    @Test
    fun testPreviewValueNormalizesWhitespaceAndTruncates() {
        val preview = AcpProtocolDebugLogger.previewValue("  abc\tdef\n" + "x".repeat(200))

        assertTrue(preview.startsWith("abc def "))
        assertEquals(160, preview.length)
        assertTrue(preview.endsWith("..."))
    }
}
