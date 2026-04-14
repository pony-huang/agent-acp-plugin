package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.AvailableCommand
import com.agentclientprotocol.model.AvailableCommandInput
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.acp.PermissionRequestHandler
import com.github.ponyhuang.agentacpplugin.services.acp.SessionUpdateIngress
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot
import com.github.ponyhuang.agentacpplugin.services.session.AgentEndpointStore
import com.github.ponyhuang.agentacpplugin.services.session.ConversationTurnStore
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path

@OptIn(UnstableApi::class)
class AcpProjectServiceTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InMemoryProjectSessionCoordinator
    private lateinit var service: AcpProjectService

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinator = InMemoryProjectSessionCoordinator()
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
        coordinator.nextSessionId = "session-1"

        service.connect("npx @agentclientprotocol/claude-agent-acp")

        val snapshot = snapshot("session-1")
        assertEquals("session-1", service.selectedSessionId())
        assertEquals("npx", snapshot.headerState.title)
        assertEquals("CONNECTED", snapshot.headerState.connectionStatus)
        assertEquals("IDLE", snapshot.headerState.sessionStatus)
        assertTrue(snapshot.composerEnabled)
        assertNull(snapshot.bannerState)
        assertTrue(snapshot.visibleTimeline.isEmpty())
    }

    fun testSubmitPromptUsesRealIngressMapperAndStoreFlow() {
        connectSession("session-1")
        coordinator.submitPromptBehavior = { sessionId, _, ingress ->
            ingress.onSessionUpdate(sessionId, SessionUpdate.AgentMessageChunk(ContentBlock.Text("ACP_OK")))
            ingress.onPromptFinished(sessionId, TurnCompletionReason.END_TURN)
        }

        service.submitPrompt("hello ACP")

        val snapshot = snapshot("session-1")
        assertEquals("IDLE", snapshot.headerState.sessionStatus)
        assertEquals(listOf("hello ACP", "ACP_OK"), snapshot.visibleTimeline.map { it.textContent })
        assertEquals(listOf("COMPLETED", "COMPLETED"), snapshot.visibleTimeline.map { it.displayState.name })
        assertNull(snapshot.bannerState)
    }

    fun testSubmitPromptFailureShowsBannerAndStatusItem() {
        connectSession("session-1")
        coordinator.submitPromptBehavior = { _, _, _ ->
            error("agent offline")
        }

        service.submitPrompt("hello")

        val snapshot = snapshot("session-1")
        assertEquals("DEGRADED", snapshot.headerState.sessionStatus)
        assertEquals("agent offline", snapshot.bannerState?.text)
        assertEquals(listOf("hello", "agent offline"), snapshot.visibleTimeline.map { it.textContent })
        assertEquals("FAILED", snapshot.visibleTimeline.last().displayState.name)
    }

    fun testSessionUpdatesRefreshSnapshotMetadata() {
        connectSession("session-1")

        service.onSessionUpdate(
            "session-1",
            SessionUpdate.SessionInfoUpdate(title = "Review Session"),
        )
        service.onSessionUpdate(
            "session-1",
            SessionUpdate.CurrentModeUpdate(SessionModeId("review")),
        )
        service.onSessionUpdate(
            "session-1",
            SessionUpdate.AvailableCommandsUpdate(
                listOf(
                    AvailableCommand(name = "plan", description = "Show plan"),
                    AvailableCommand(
                        name = "mode",
                        description = "Switch mode",
                        input = AvailableCommandInput.Unstructured("mode id"),
                    ),
                ),
            ),
        )
        service.onSessionUpdate(
            "session-1",
            SessionUpdate.ConfigOptionUpdate(
                listOf(
                    SessionConfigOption.boolean(
                        id = "auto-apply",
                        name = "Auto apply",
                        currentValue = true,
                    ),
                    SessionConfigOption.select(
                        id = "model",
                        name = "Model",
                        currentValue = "gpt-5.4",
                        options = SessionConfigSelectOptions.Flat(
                            listOf(
                                SessionConfigSelectOption(
                                    value = com.agentclientprotocol.model.SessionConfigValueId("gpt-5.4"),
                                    name = "GPT-5.4",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        service.onSessionUpdate(
            "session-1",
            SessionUpdate.UsageUpdate(
                used = 42,
                size = 256,
                cost = Cost(amount = 0.12, currency = "USD"),
            ),
        )

        val snapshot = snapshot("session-1")
        assertEquals("Review Session", snapshot.headerState.title)
        assertEquals("review", snapshot.headerState.currentMode)
        assertEquals("Used 42 / 256 (0.12 USD)", snapshot.headerState.usageSummary)
        assertEquals(listOf("/plan", "/mode - Switch mode"), snapshot.availableCommands)
        assertEquals(2, snapshot.configOptions.size)
        assertTrue(snapshot.configOptions.any { it.contains("auto-apply") })
        assertTrue(snapshot.configOptions.any { it.contains("gpt-5.4") })
    }

    fun testDisconnectSessionMarksSnapshotClosed() {
        connectSession("session-1")

        service.disconnectSession("session-1")

        val snapshot = snapshot("session-1")
        assertEquals("CLOSED", snapshot.headerState.sessionStatus)
        assertEquals("Session disconnected", snapshot.bannerState?.text)
        assertFalse(snapshot.composerEnabled)
    }

    private fun connectSession(sessionId: String) {
        coordinator.nextSessionId = sessionId
        service.connect("npx @agentclientprotocol/claude-agent-acp")
    }

    private fun snapshot(sessionId: String): SessionViewSnapshot =
        requireNotNull(service.snapshots()[sessionId]) { "Expected snapshot for $sessionId" }
}

private class InMemoryProjectSessionCoordinator : ProjectSessionCoordinator {
    var nextSessionId: String = "session-1"
    var submitPromptBehavior: suspend (String, String, SessionUpdateIngress) -> Unit = { _, _, _ -> }

    override suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): ConnectedSession = ConnectedSession(nextSessionId)

    override suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
        submitPromptBehavior(sessionId, prompt, ingress)
    }

    override suspend fun cancel(sessionId: String) = Unit

    override fun disconnect(sessionId: String) = Unit
}
