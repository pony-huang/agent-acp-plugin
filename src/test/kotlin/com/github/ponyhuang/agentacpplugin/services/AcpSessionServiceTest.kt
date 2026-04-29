package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionResponse
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

    fun testToolCallUpdatePreservesExistingOldTextWhenIncomingDiffOmitsBaseline() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))
        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-diff-merge"),
                title = "Apply patch",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = "original text",
                        newText = "first edit"
                    )
                )
            )
        )

        service.applySessionUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-diff-merge"),
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = null,
                        newText = "second edit"
                    )
                )
            )
        )

        val diff = service.messages.value.single().toolCalls.single().diffContents.single()
        assertEquals("src/Main.kt", diff.path)
        assertEquals("original text", diff.oldText)
        assertEquals("second edit", diff.newText)
    }

    fun testToolCallUpdateAppendsNewDiffPathWithoutDroppingExistingDiffs() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))
        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-diff-append"),
                title = "Apply patch",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = "main old",
                        newText = "main new"
                    )
                )
            )
        )

        service.applySessionUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-diff-append"),
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Other.kt",
                        oldText = "other old",
                        newText = "other new"
                    )
                )
            )
        )

        val diffs = service.messages.value.single().toolCalls.single().diffContents
        assertEquals(2, diffs.size)
        assertEquals("src/Main.kt", diffs[0].path)
        assertEquals("main old", diffs[0].oldText)
        assertEquals("main new", diffs[0].newText)
        assertEquals("src/Other.kt", diffs[1].path)
        assertEquals("other old", diffs[1].oldText)
        assertEquals("other new", diffs[1].newText)
    }

    fun testToolCallUpdateKeepsFirstBaselineAcrossMultipleUpdatesForSamePath() {
        service.applySessionUpdate(SessionUpdate.AgentMessageChunk(ContentBlock.Text("Working")))
        service.applySessionUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-diff-multi"),
                title = "Apply patch",
                kind = ToolKind.EDIT,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = "original text",
                        newText = "first edit"
                    )
                )
            )
        )

        service.applySessionUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-diff-multi"),
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = null,
                        newText = "second edit"
                    )
                )
            )
        )
        service.applySessionUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-diff-multi"),
                content = listOf(
                    ToolCallContent.Diff(
                        path = "src/Main.kt",
                        oldText = null,
                        newText = "third edit"
                    )
                )
            )
        )

        val diff = service.messages.value.single().toolCalls.single().diffContents.single()
        assertEquals("src/Main.kt", diff.path)
        assertEquals("original text", diff.oldText)
        assertEquals("third edit", diff.newText)
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

    fun testShouldReuseCurrentClientReturnsTrueForSameAgentWhenClientExists() {
        val agent = testAgent(id = "agent-a", displayName = "Agent A")
        setClient(dummyClient())
        setCurrentAgentDefinition(agent)

        assertTrue(shouldReuseCurrentClient(agent))
    }

    fun testShouldReuseCurrentClientReturnsFalseForDifferentAgent() {
        setClient(dummyClient())
        setCurrentAgentDefinition(testAgent(id = "agent-a", displayName = "Agent A"))

        assertFalse(shouldReuseCurrentClient(testAgent(id = "agent-b", displayName = "Agent B")))
    }

    fun testResetSessionDataPreservingAgentBindingKeepsCurrentAgent() {
        val agent = testAgent(id = "agent-a", displayName = "Agent A")
        setCurrentAgentDefinition(agent)
        setCurrentAgentInfo(agent.displayName)
        service.addMessage("assistant", "existing")

        resetSessionData(keepConnectedFlag = true, clearAgentBinding = false)

        assertEquals(agent.displayName, service.currentAgent.value?.implementation?.name)
        assertTrue(service.messages.value.isEmpty())
    }

    fun testResetSessionDataClearsCurrentSessionEvenWhenPreservingAgentBinding() {
        val agent = testAgent(id = "agent-a", displayName = "Agent A")
        setCurrentAgentDefinition(agent)
        setCurrentAgentInfo(agent.displayName)
        setCurrentSession(dummySession())

        resetSessionData(keepConnectedFlag = true, clearAgentBinding = false)

        assertNull(currentSession())
    }

    fun testCanReuseClientForSessionListingReturnsTrueWhenSameAgentAndIdle() {
        val agent = testAgent(id = "agent-a", displayName = "Agent A")
        setClient(dummyClient())
        setCurrentAgentDefinition(agent)
        setLoading(false)
        setConnecting(false)

        assertTrue(canReuseClientForSessionListing(agent))
    }

    fun testCanReuseClientForSessionListingReturnsFalseWhenLoading() {
        val agent = testAgent(id = "agent-a", displayName = "Agent A")
        setClient(dummyClient())
        setCurrentAgentDefinition(agent)
        setLoading(true)
        setConnecting(false)

        assertFalse(canReuseClientForSessionListing(agent))
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

    fun testHandleSessionUpdateFromClientIgnoresStaleClientToken() {
        setActiveClientToken("current-token")

        invokeHandleSessionUpdateFromClient(
            clientToken = "stale-token",
            update = SessionUpdate.AgentMessageChunk(ContentBlock.Text("stale"))
        )

        assertTrue(service.messages.value.isEmpty())

        invokeHandleSessionUpdateFromClient(
            clientToken = "current-token",
            update = SessionUpdate.AgentMessageChunk(ContentBlock.Text("fresh"))
        )

        assertEquals("fresh", service.messages.value.single().content)
    }

    private fun setPendingPromptEcho(value: String) {
        val field = AcpSessionService::class.java.getDeclaredField("pendingPromptEchoRemainder")
        field.isAccessible = true
        field.set(service, value)
    }

    private fun setActiveClientToken(value: String?) {
        val field = AcpSessionService::class.java.getDeclaredField("activeClientToken")
        field.isAccessible = true
        field.set(service, value)
    }

    private fun invokeHandleSessionUpdateFromClient(clientToken: String, update: SessionUpdate) {
        val method = AcpSessionService::class.java.getDeclaredMethod(
            "handleSessionUpdateFromClient",
            String::class.java,
            SessionUpdate::class.java
        )
        method.isAccessible = true
        method.invoke(service, clientToken, update)
    }

    private fun setCurrentSession(session: ClientSession) {
        val field = AcpSessionService::class.java.getDeclaredField("_currentSession")
        field.isAccessible = true
        field.set(service, session)
    }

    private fun currentSession(): ClientSession? {
        val field = AcpSessionService::class.java.getDeclaredField("_currentSession")
        field.isAccessible = true
        return field.get(service) as? ClientSession
    }

    private fun setClient(value: AcpAgentClient?) {
        val field = AcpSessionService::class.java.getDeclaredField("client")
        field.isAccessible = true
        field.set(service, value)
    }

    private fun setCurrentAgentDefinition(agent: AgentRegistry.InstalledAgent?) {
        val field = AcpSessionService::class.java.getDeclaredField("currentAgentDefinition")
        field.isAccessible = true
        field.set(service, agent)
    }

    private fun setCurrentAgentInfo(name: String) {
        val field = AcpSessionService::class.java.getDeclaredField("_currentAgent")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<com.agentclientprotocol.agent.AgentInfo?>
        flow.value = com.agentclientprotocol.agent.AgentInfo(
            implementation = Implementation(name = name, version = "1.0.0"),
            capabilities = com.agentclientprotocol.model.AgentCapabilities(
                loadSession = true
            )
        )
    }

    private fun setLoading(value: Boolean) {
        val field = AcpSessionService::class.java.getDeclaredField("_isLoading")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<Boolean>
        flow.value = value
    }

    private fun setConnecting(value: Boolean) {
        val field = AcpSessionService::class.java.getDeclaredField("_isConnecting")
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

    private fun shouldReuseCurrentClient(agent: AgentRegistry.InstalledAgent): Boolean {
        val method = AcpSessionService::class.java.getDeclaredMethod(
            "shouldReuseCurrentClient",
            AgentRegistry.InstalledAgent::class.java
        )
        method.isAccessible = true
        return method.invoke(service, agent) as Boolean
    }

    private fun resetSessionData(keepConnectedFlag: Boolean, clearAgentBinding: Boolean) {
        val method = AcpSessionService::class.java.getDeclaredMethod(
            "resetSessionData",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(service, keepConnectedFlag, clearAgentBinding)
    }

    private fun canReuseClientForSessionListing(agent: AgentRegistry.InstalledAgent): Boolean {
        val method = AcpSessionService::class.java.getDeclaredMethod(
            "canReuseClientForSessionListing",
            AgentRegistry.InstalledAgent::class.java
        )
        method.isAccessible = true
        return method.invoke(service, agent) as Boolean
    }

    private fun dummyClient(): AcpAgentClient {
        return AcpAgentClient(
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            project = project,
            cmd = listOf("dummy"),
            envs = emptyList(),
            sessionUpdateSink = {},
            permissionRequestSink = { _, permissions, meta ->
                RequestPermissionResponse(
                    RequestPermissionOutcome.Selected(permissions.first().optionId),
                    meta
                )
            }
        )
    }

    private fun dummySession(): ClientSession {
        return Proxy.newProxyInstance(
            ClientSession::class.java.classLoader,
            arrayOf(ClientSession::class.java)
        ) { _, method, _ ->
            when (method.name) {
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
    }

    private fun testAgent(id: String, displayName: String) = AgentRegistry.InstalledAgent(
        registryAgentId = id,
        id = id,
        displayName = displayName,
        description = "Description",
        version = "1.0.0",
        iconPath = null,
        installMethod = InstallMethod.NPX,
        sourceLabel = "Official",
        command = "npx",
        args = emptyList(),
        env = emptyMap(),
        isLegacy = false
    )
}
