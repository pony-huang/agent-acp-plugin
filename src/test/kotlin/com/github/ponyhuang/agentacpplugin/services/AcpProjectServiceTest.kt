package com.github.ponyhuang.agentacpplugin.services

import com.github.ponyhuang.agentacpplugin.services.acp.PermissionRequestHandler
import com.github.ponyhuang.agentacpplugin.services.acp.SessionUpdateIngress
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointStore
import com.github.ponyhuang.agentacpplugin.services.session.ConversationTurnStore
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path

class AcpProjectServiceTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: FakeProjectSessionCoordinator
    private lateinit var service: AcpProjectService

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinator = FakeProjectSessionCoordinator()
        service = AcpProjectService(
            project = project,
            scope = scope,
            sessionCoordinator = coordinator,
            endpointStore = AgentEndpointStore(),
            turnStore = ConversationTurnStore(),
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

    fun testConnectCreatesConnectedSnapshotAndSelectsSession() {
        coordinator.connectResult = ConnectedSession("session-1")

        service.connect("npx @agentclientprotocol/claude-agent-acp")

        val snapshot = service.snapshots().getValue("session-1")
        assertEquals("session-1", service.selectedSessionId())
        assertEquals("npx", snapshot.headerState.title)
        assertEquals("CONNECTED", snapshot.headerState.connectionStatus)
        assertEquals("IDLE", snapshot.headerState.sessionStatus)
        assertTrue(snapshot.composerEnabled)
        assertEquals("npx", coordinator.connectCalls.single().endpointName)
        assertEquals("npx @agentclientprotocol/claude-agent-acp", coordinator.connectCalls.single().commandLine)
    }

    fun testSubmitPromptStartsTurnAndFinishesSuccessfully() {
        connectSession()
        coordinator.submitPromptHook = { sessionId, _, ingress ->
            ingress.onPromptFinished(sessionId, TurnCompletionReason.END_TURN)
        }

        service.submitPrompt("hello ACP")

        val snapshot = service.snapshots().getValue("session-1")
        assertEquals("session-1", coordinator.promptCalls.single().sessionId)
        assertEquals("hello ACP", coordinator.promptCalls.single().prompt)
        assertEquals("IDLE", snapshot.headerState.sessionStatus)
        assertEquals(listOf("hello ACP"), snapshot.visibleTimeline.map { it.textContent })
    }

    fun testSubmitPromptFailureShowsBannerAndStatusItem() {
        connectSession()
        coordinator.submitPromptFailure = IllegalStateException("agent offline")

        service.submitPrompt("hello")

        val snapshot = service.snapshots().getValue("session-1")
        assertEquals("DEGRADED", snapshot.headerState.sessionStatus)
        assertEquals("agent offline", snapshot.bannerState?.text)
        assertEquals(listOf("hello", "agent offline"), snapshot.visibleTimeline.map { it.textContent })
    }

    fun testCancelAndDisconnectUseSelectedSession() {
        connectSession()

        service.cancelSelectedPrompt()
        service.disconnectSession("session-1")

        val snapshot = service.snapshots().getValue("session-1")
        assertEquals(listOf("session-1"), coordinator.cancelledSessionIds)
        assertEquals(listOf("session-1"), coordinator.disconnectedSessionIds)
        assertEquals("CLOSED", snapshot.headerState.sessionStatus)
        assertEquals("Session disconnected", snapshot.bannerState?.text)
        assertFalse(snapshot.composerEnabled)
    }

    private fun connectSession(sessionId: String = "session-1") {
        coordinator.connectResult = ConnectedSession(sessionId)
        service.connect("npx @agentclientprotocol/claude-agent-acp")
    }
}

private class FakeProjectSessionCoordinator : ProjectSessionCoordinator {
    var connectResult: ConnectedSession = ConnectedSession("session-1")
    var submitPromptFailure: Throwable? = null
    var submitPromptHook: (suspend (String, String, SessionUpdateIngress) -> Unit)? = null

    val connectCalls = mutableListOf<ConnectCall>()
    val promptCalls = mutableListOf<PromptCall>()
    val cancelledSessionIds = mutableListOf<String>()
    val disconnectedSessionIds = mutableListOf<String>()

    override suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): ConnectedSession {
        connectCalls += ConnectCall(endpointId, endpointName, commandLine, workspaceRoot)
        return connectResult
    }

    override suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
        promptCalls += PromptCall(sessionId, prompt)
        submitPromptFailure?.let { throw it }
        submitPromptHook?.invoke(sessionId, prompt, ingress)
    }

    override suspend fun cancel(sessionId: String) {
        cancelledSessionIds += sessionId
    }

    override fun disconnect(sessionId: String) {
        disconnectedSessionIds += sessionId
    }
}

private data class ConnectCall(
    val endpointId: String,
    val endpointName: String,
    val commandLine: String,
    val workspaceRoot: Path,
)

private data class PromptCall(
    val sessionId: String,
    val prompt: String,
)
