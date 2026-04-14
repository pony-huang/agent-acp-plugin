package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.*
import com.github.ponyhuang.agentacpplugin.services.acp.PendingPermissionRequest
import com.github.ponyhuang.agentacpplugin.services.acp.PermissionRequestHandler
import com.github.ponyhuang.agentacpplugin.services.acp.SessionUpdateIngress
import com.github.ponyhuang.agentacpplugin.services.session.AgentConnectionStatus
import com.github.ponyhuang.agentacpplugin.services.session.SessionStatus
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.nio.file.Path

@OptIn(UnstableApi::class)
class AcpProjectServiceTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var service: AcpProjectService
    private lateinit var sessionCoordinator: FakeProjectSessionCoordinator

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        sessionCoordinator = FakeProjectSessionCoordinator()
        service = AcpProjectService(
            project = project,
            scope = scope,
            sessionCoordinator = sessionCoordinator,
        )
    }

    override fun tearDown() {
        try {
            service.dispose()
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testSessionViewsStateFlowTracksConnectionAndPromptLifecycle() {
        assertTrue(service.sessionViews.value.isEmpty())

        service.connect("npx.cmd --@agentclientprotocol/claude-agent-acp")

        val endpoint = service.dependencies.endpointStore.allEndpoints().single()
        assertEquals(AgentConnectionStatus.CONNECTED, endpoint.connectionStatus)
        assertEquals("npx.cmd", endpoint.displayName)

        val sessionId = service.selectedSessionId()
        assertNotNull(sessionId)
        val connectedView = service.sessionViews.value.getValue(sessionId!!)
        assertEquals("CONNECTED", connectedView.headerState.connectionStatus)
        assertEquals("IDLE", connectedView.headerState.sessionStatus)
        assertTrue(connectedView.composerEnabled)
        assertTrue(connectedView.visibleTimeline.isEmpty())

        service.submitPrompt("hello ACP")

        assertEquals(listOf(sessionId to "hello ACP"), sessionCoordinator.submittedPrompts)

        val updatedSession = service.dependencies.endpointStore.getSession(sessionId)
        assertNotNull(updatedSession)
        assertEquals(SessionStatus.STREAMING, updatedSession!!.sessionStatus)

        val streamingView = service.sessionViews.value.getValue(sessionId)
        assertEquals("STREAMING", streamingView.headerState.sessionStatus)
        assertTrue(streamingView.composerEnabled)
        assertEquals(listOf("hello ACP"), streamingView.visibleTimeline.map { it.textContent })
    }

    fun testPendingPermissionRequestFlowReflectsHandlerState() = runBlocking {
        val handler = PermissionRequestHandler()
        val observed = mutableListOf<PendingPermissionRequest?>()
        val collectJob = launch(Dispatchers.Unconfined) {
            handler.pendingRequest.take(3).toList(observed)
        }

        handler.request(
            toolCall = SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-1"),
                title = "Write File",
            ),
            permissions = listOf(
                PermissionOption(
                    optionId = PermissionOptionId("allow-once"),
                    name = "Allow once",
                    kind = PermissionOptionKind.ALLOW_ONCE,
                ),
                PermissionOption(
                    optionId = PermissionOptionId("reject-once"),
                    name = "Reject once",
                    kind = PermissionOptionKind.REJECT_ONCE,
                ),
            ),
            _meta = null,
        )

        collectJob.join()

        assertEquals(
            listOf(
                null,
                PendingPermissionRequest(toolTitle = "Write File", options = listOf("Allow once", "Reject once")),
                null,
            ),
            observed,
        )
        assertNull(handler.pendingRequest.value)
    }
}

private class FakeProjectSessionCoordinator(
    private val nextSessionId: String = "session-1",
) : ProjectSessionCoordinator {
    val submittedPrompts = mutableListOf<Pair<String, String>>()

    override suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): String = nextSessionId

    override suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
        submittedPrompts += sessionId to prompt
    }

    override suspend fun cancel(sessionId: String) = Unit

    override fun disconnect(sessionId: String) = Unit
}
