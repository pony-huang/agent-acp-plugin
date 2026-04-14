package com.github.ponyhuang.agentacpplugin.services.render

import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointState
import com.github.ponyhuang.agentacpplugin.services.session.ConversationSessionState
import com.github.ponyhuang.agentacpplugin.services.session.SessionStatus
import com.github.ponyhuang.agentacpplugin.services.session.ToolCallState
import com.github.ponyhuang.agentacpplugin.services.session.ToolLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionViewStateAssemblerTest {
    private val assembler = SessionViewStateAssembler()

    @Test
    fun testAssembleSortsTimelineAndDecoratesToolMetadata() {
        val view = assembler.assemble(
            endpoint = AgentEndpointState(
                endpointId = "endpoint-1",
                displayName = "agent",
                connectionStatus = AgentConnectionStatus.CONNECTED,
            ),
            session = ConversationSessionState(
                sessionId = "session-1",
                endpointId = "endpoint-1",
                title = "Agent",
                sessionStatus = SessionStatus.STREAMING,
            ),
            timeline = listOf(
                TimelineItem(
                    itemId = "item-2",
                    turnId = "turn-1",
                    itemType = TimelineItemType.TOOL_RESULT,
                    displayState = TimelineDisplayState.COMPLETED,
                    sequenceNumber = 2,
                    title = "ReadFile",
                    textContent = "done",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                    metadata = mapOf("toolCallId" to "tool-1"),
                ),
                TimelineItem(
                    itemId = "item-1",
                    turnId = "turn-1",
                    itemType = TimelineItemType.USER_MESSAGE,
                    displayState = TimelineDisplayState.COMPLETED,
                    sequenceNumber = 1,
                    title = "You",
                    textContent = "hello",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            ),
            toolCalls = listOf(
                ToolCallState(
                    toolCallId = "tool-1",
                    turnId = "turn-1",
                    toolName = "ReadFile",
                    status = ToolLifecycleStatus.SUCCEEDED,
                    resultSummary = "ok",
                ),
            ),
        )

        assertEquals(listOf("hello", "done"), view.visibleTimeline.map { it.textContent })
        assertEquals("SUCCEEDED", view.visibleTimeline.last().metadata["toolStatus"])
        assertEquals("ok", view.visibleTimeline.last().metadata["toolResult"])
        assertTrue(view.composerEnabled)
    }

    @Test
    fun testAssembleBuildsErrorBannerAndDisablesComposerForClosedSession() {
        val view = assembler.assemble(
            endpoint = AgentEndpointState(
                endpointId = "endpoint-1",
                displayName = "agent",
                connectionStatus = AgentConnectionStatus.FAILED,
                lastErrorSummary = "Connection failed",
            ),
            session = ConversationSessionState(
                sessionId = "session-1",
                endpointId = "endpoint-1",
                sessionStatus = SessionStatus.CLOSED,
            ),
            timeline = emptyList(),
            toolCalls = emptyList(),
        )

        assertFalse(view.composerEnabled)
        val bannerState = view.bannerState
        assertNotNull(bannerState)
        requireNotNull(bannerState)
        assertEquals("Connection failed", bannerState.text)
        assertTrue(bannerState.isError)
    }
}
