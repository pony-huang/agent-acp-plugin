package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallLocation
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.client.ClientSession
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy

class AcpSessionServiceTest : BasePlatformTestCase() {

    private lateinit var service: AcpSessionService
    private lateinit var permissionRequestService: AcpPermissionRequestService

    override fun setUp() {
        super.setUp()
        service = project.service()
        permissionRequestService = project.service()
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
        assertEquals(
            listOf(
                AcpSessionService.MessageEntry.Thought("thinking"),
                AcpSessionService.MessageEntry.Content("answer")
            ),
            message.entries
        )
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
        assertEquals(
            listOf(
                AcpSessionService.ToolCallLocationInfo(
                    displayText = "src/Main.kt:12",
                    path = "src/Main.kt",
                    line = 12
                )
            ),
            toolCall.locations
        )
        assertNull(toolCall.contentSummary)
        assertTrue(messages.single().entries.any { it is AcpSessionService.MessageEntry.ToolCall })
    }

    fun testNonReadToolCallKeepsContentSummary() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))

        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-search"),
                title = "Search workspace",
                kind = ToolKind.SEARCH,
                status = ToolCallStatus.IN_PROGRESS
            )
        )
        service.applySessionUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-search"),
                content = listOf(ToolCallContent.Terminal("term-search"))
            )
        )

        val toolCall = service.messages.value.single().toolCalls.single()
        assertEquals("search", toolCall.kind)
        assertEquals("Terminal: term-search", toolCall.contentSummary)
    }

    fun testToolCallPreservesStructuredDiffContent() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))
        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-diff"),
                title = "Apply patch",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = "old text",
                        newText = "new text"
                    )
                )
            )
        )

        val toolCall = service.messages.value.single().toolCalls.single()
        assertEquals(1, toolCall.diffContents.size)
        assertEquals("src/Main.kt", toolCall.diffContents.single().path)
        assertEquals("old text", toolCall.diffContents.single().oldText)
        assertEquals("new text", toolCall.diffContents.single().newText)
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

    fun testBuildSessionConnectTimeoutExceptionUsesConnectingMessage() {
        val exception = service.buildSessionConnectTimeoutException(
            agentDisplayName = "Claude Agent",
            reconnecting = false
        )

        assertEquals(
            "Timed out while connecting to 'Claude Agent'. Check whether the agent is installed and can start in ACP mode.",
            exception.message
        )
        assertNull(exception.cause)
    }

    fun testBuildSessionConnectTimeoutExceptionUsesReconnectingMessage() {
        val exception = service.buildSessionConnectTimeoutException(
            agentDisplayName = "Claude Agent",
            reconnecting = true
        )

        assertEquals(
            "Timed out while reconnecting to 'Claude Agent'. Check whether the agent is installed and can start in ACP mode.",
            exception.message
        )
        assertNull(exception.cause)
    }

    fun testCancelImmediatelyLeavesRunningStateAndMarksPromptCancelled() = runBlocking {
        var cancelCalled = false
        setCurrentSession(
            Proxy.newProxyInstance(
                ClientSession::class.java.classLoader,
                arrayOf(ClientSession::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> {
                        cancelCalled = true
                        Unit
                    }
                    "getCurrentMode" -> MutableStateFlow(SessionModeId("default"))
                    "getCurrentModel" -> MutableStateFlow(ModelId("gpt-5"))
                    "getAvailableModes",
                    "getAvailableModels" -> emptyList<Any>()
                    "getModesSupported",
                    "getModelsSupported",
                    "getConfigOptionsSupported" -> false
                    else -> null
                }
            } as ClientSession
        )
        setLoading(true)
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))
        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-cancel"),
                title = "Run command",
                kind = ToolKind.EXECUTE,
                status = ToolCallStatus.IN_PROGRESS
            )
        )

        service.cancel()

        assertTrue(cancelCalled)
        assertFalse(service.isLoading.value)
        assertEquals(StopReason.CANCELLED, service.lastStopReason.value)
        assertNotNull(service.sessionUpdatedAt.value)
        assertEquals("cancelled", service.messages.value.single().toolCalls.single().status)
        assertTrue(service.activeToolCalls.value.isEmpty())
    }

    fun testCancelledPromptResponseMarksActiveToolCallsCancelled() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))
        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-response-cancel"),
                title = "Search workspace",
                kind = ToolKind.SEARCH,
                status = ToolCallStatus.IN_PROGRESS
            )
        )

        service.applyPromptResponse(PromptResponse(stopReason = StopReason.CANCELLED))

        assertEquals("cancelled", service.messages.value.single().toolCalls.single().status)
        assertTrue(service.activeToolCalls.value.isEmpty())
    }

    fun testPermissionRequestIsExposedAndCanBeSubmitted() = runBlocking {
        val deferredResponse = async(Dispatchers.Default) {
            permissionRequestService.requestPermissions(
                toolCall = SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tool-approve"),
                    title = "Run command",
                    status = ToolCallStatus.PENDING
                ),
                permissions = listOf(
                    PermissionOption(
                        optionId = PermissionOptionId("allow-once"),
                        name = "Allow once",
                        kind = PermissionOptionKind.ALLOW_ONCE
                    ),
                    PermissionOption(
                        optionId = PermissionOptionId("reject-once"),
                        name = "Reject once",
                        kind = PermissionOptionKind.REJECT_ONCE
                    )
                ),
                meta = null
            )
        }

        waitForCondition {
            service.pendingPermissionRequests.value.size == 1
        }

        val request = service.pendingPermissionRequests.value.single()
        assertEquals("Run command", request.title)
        assertEquals("allow-once", request.selectedOptionId)
        assertFalse(request.submitted)
        assertEquals(2, request.options.size)
        assertEquals("Allow once", request.options.first().label)
        val embeddedRequest = service.messages.value.last().entries.last() as AcpSessionService.MessageEntry.PermissionRequest
        assertEquals(request.requestId, embeddedRequest.request.requestId)

        assertTrue(service.submitPermissionRequest(request.requestId, "reject-once"))

        waitForCondition {
            service.pendingPermissionRequests.value.single().submitted
        }

        val updatedRequest = service.pendingPermissionRequests.value.single()
        assertEquals("reject-once", updatedRequest.selectedOptionId)
        assertTrue(updatedRequest.submitted)
        val updatedEmbeddedRequest =
            service.messages.value.last().entries.last() as AcpSessionService.MessageEntry.PermissionRequest
        assertEquals("reject-once", updatedEmbeddedRequest.request.selectedOptionId)
        assertTrue(updatedEmbeddedRequest.request.submitted)

        val response = deferredResponse.await()
        val outcome = response.outcome as RequestPermissionOutcome.Selected
        assertEquals("reject-once", outcome.optionId.value)
    }

    fun testClearMessagesResetsPendingPermissionRequests() = runBlocking {
        val deferredResponse = async(Dispatchers.Default) {
            permissionRequestService.requestPermissions(
                toolCall = SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId("tool-clear"),
                    title = "Open file",
                    status = ToolCallStatus.PENDING
                ),
                permissions = listOf(
                    PermissionOption(
                        optionId = PermissionOptionId("allow"),
                        name = "Allow",
                        kind = PermissionOptionKind.ALLOW_ONCE
                    )
                ),
                meta = null
            )
        }

        waitForCondition {
            service.pendingPermissionRequests.value.isNotEmpty()
        }

        service.clearMessages()

        assertTrue(service.pendingPermissionRequests.value.isEmpty())

        val requestId = waitForNonNull { permissionRequestServiceRequestId() }
        assertTrue(permissionRequestService.submitSelection(requestId, PermissionOptionId("allow")))
        deferredResponse.await()
    }

    private fun setPendingPromptEcho(value: String) {
        val field = AcpSessionService::class.java.getDeclaredField("pendingPromptEchoRemainder")
        field.isAccessible = true
        field.set(service, value)
    }

    private fun setCurrentSession(session: ClientSession) {
        val field = AcpSessionService::class.java.getDeclaredField("_currentSession")
        field.isAccessible = true
        field.set(service, session)
    }

    private fun setLoading(value: Boolean) {
        val field = AcpSessionService::class.java.getDeclaredField("_isLoading")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<Boolean>
        flow.value = value
    }

    private suspend fun waitForCondition(predicate: () -> Boolean) {
        var satisfied = false
        withContext(Dispatchers.Default) {
            repeat(100) {
                if (predicate()) {
                    satisfied = true
                    return@withContext
                }
                Thread.sleep(10)
            }
        }
        if (!satisfied) {
            fail("Timed out waiting for condition")
        }
    }

    private suspend fun waitForNonNull(valueProvider: () -> String?): String {
        var result: String? = null
        waitForCondition {
            result = valueProvider()
            result != null
        }
        return result!!
    }

    private fun permissionRequestServiceRequestId(): String? {
        val field = AcpPermissionRequestService::class.java.getDeclaredField("pendingRequests")
        field.isAccessible = true
        val requests = field.get(permissionRequestService) as java.util.concurrent.ConcurrentHashMap<*, *>
        return requests.keys.firstOrNull() as? String
    }
}
