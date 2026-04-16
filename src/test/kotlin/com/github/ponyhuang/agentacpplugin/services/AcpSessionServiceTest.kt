package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AcpSessionServiceTest : BasePlatformTestCase() {

    private lateinit var service: AcpSessionService

    override fun setUp() {
        super.setUp()
        service = project.service()
        service.clearMessages()
    }

    fun testUserMessageChunkDoesNotDuplicateSeededPrompt() {
        service.addMessage("user", "hello")
        setPendingPromptEcho("hello")

        service.applySessionUpdate(SessionUpdate.UserMessageChunk(ContentBlock.Text("he")))
        service.applySessionUpdate(SessionUpdate.UserMessageChunk(ContentBlock.Text("llo")))

        val messages = service.messages.value
        assertEquals(1, messages.size)
        assertEquals("hello", messages.single().content)
    }

    fun testAgentThoughtAndMessageChunksMergeIntoSingleAssistantMessage() {
        service.applySessionUpdate(SessionUpdate.AgentThoughtChunk(ContentBlock.Text("thinking")))
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("answer")))

        val message = service.messages.value.single()
        assertEquals("assistant", message.role)
        assertEquals("thinking", message.thought)
        assertEquals("answer", message.content)
    }

    fun testToolCallAttachesToCurrentAssistantMessageAndUpdatesStatus() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))

        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-1"),
                title = "Read file",
                kind = ToolKind.READ,
                status = ToolCallStatus.PENDING,
                locations = listOf(ToolCallLocation(path = "src/Main.kt", line = 12u))
            )
        )
        service.applySessionUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-1"),
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(ToolCallContent.Terminal("term-1"))
            )
        )

        val messages = service.messages.value
        assertEquals(1, messages.size)
        val toolCall = messages.single().toolCalls.single()
        assertEquals("read", toolCall.kind)
        assertEquals("in_progress", toolCall.status)
        assertEquals(listOf("src/Main.kt:12"), toolCall.locations)
        assertEquals("Terminal: term-1", toolCall.contentSummary)
    }

    fun testNonTextContentProducesVisiblePlaceholder() {
        service.applySessionUpdate(
            SessionUpdate.AgentMessageChunk(
                ContentBlock.Resource(
                    EmbeddedResourceResource.TextResourceContents(
                        text = "content",
                        uri = "file:///tmp/readme.md"
                    )
                )
            )
        )

        assertEquals("[Embedded Resource: file:///tmp/readme.md]", service.messages.value.single().content)
    }

    fun testSessionInfoUpdateAndClearMessagesResetConversationStatus() {
        service.applySessionUpdate(
            SessionUpdate.SessionInfoUpdate(
                title = "ACP Session",
                updatedAt = "2026-04-16T12:30:00Z"
            )
        )

        assertEquals("ACP Session", service.sessionTitle.value)
        assertEquals(1776342600000L, service.sessionUpdatedAt.value)

        service.clearMessages()

        assertNull(service.sessionTitle.value)
        assertNull(service.sessionUpdatedAt.value)
        assertTrue(service.latestPlanEntries.value.isEmpty())
        assertNull(service.latestUsage.value)
    }

    fun testPlanAndUsageUpdatesPopulateUiState() {
        service.applySessionUpdate(
            SessionUpdate.PlanUpdate(
                entries = listOf(
                    PlanEntry(
                        content = "Inspect files",
                        priority = PlanEntryPriority.HIGH,
                        status = PlanEntryStatus.IN_PROGRESS
                    ),
                    PlanEntry(
                        content = "Render panel",
                        priority = PlanEntryPriority.MEDIUM,
                        status = PlanEntryStatus.PENDING
                    )
                )
            )
        )
        service.applySessionUpdate(
            SessionUpdate.UsageUpdate(
                used = 120,
                size = 800,
                cost = Cost(amount = 0.42, currency = "USD")
            )
        )

        assertEquals(2, service.latestPlanEntries.value.size)
        assertEquals("Inspect files", service.latestPlanEntries.value.first().content)
        assertEquals("high", service.latestPlanEntries.value.first().priority)
        assertEquals("in_progress", service.latestPlanEntries.value.first().status)
        assertEquals(120L, service.latestUsage.value?.usedTokens)
        assertEquals(800L, service.latestUsage.value?.totalTokens)
        assertEquals(0.42, service.latestUsage.value?.costAmount)
        assertEquals("USD", service.latestUsage.value?.costCurrency)
    }

    fun testPromptResponseUpdatesStopReasonAndDerivedTitle() {
        val prompt = "Implement a better ACP panel renderer for the tool window"
        service.addMessage("user", prompt)
        val updateTitleMethod = AcpSessionService::class.java.getDeclaredMethod(
            "updateDerivedSessionTitleFromPrompt",
            String::class.java
        )
        updateTitleMethod.isAccessible = true
        updateTitleMethod.invoke(service, prompt)

        service.applyPromptResponse(PromptResponse(stopReason = StopReason.END_TURN))

        assertEquals(prompt.take(50), service.sessionTitle.value)
        assertEquals(StopReason.END_TURN, service.lastStopReason.value)
        assertNotNull(service.sessionUpdatedAt.value)
    }

    private fun setPendingPromptEcho(value: String) {
        val field = AcpSessionService::class.java.getDeclaredField("pendingPromptEchoRemainder")
        field.isAccessible = true
        field.set(service, value)
    }
}
