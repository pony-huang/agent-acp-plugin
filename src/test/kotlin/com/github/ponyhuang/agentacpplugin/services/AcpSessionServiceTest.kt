package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.SessionUpdate
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

    private fun setPendingPromptEcho(value: String) {
        val field = AcpSessionService::class.java.getDeclaredField("pendingPromptEchoRemainder")
        field.isAccessible = true
        field.set(service, value)
    }
}
