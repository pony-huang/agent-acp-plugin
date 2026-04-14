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
import com.github.ponyhuang.agentacpplugin.services.acp.PendingPermissionRequest
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
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(UnstableApi::class)
class AcpProjectServiceTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InMemoryProjectSessionCoordinator
    private lateinit var service: AcpProjectService
    private lateinit var trace: AcpProjectServiceTestTrace
    private var removeSnapshotListener: (() -> Unit)? = null
    private var removePermissionListener: (() -> Unit)? = null

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        trace = AcpProjectServiceTestTrace()
        coordinator = InMemoryProjectSessionCoordinator(trace)
        service = AcpProjectService(
            project = project,
            scope = scope,
            sessionCoordinator = coordinator,
            endpointStore = AgentEndpointStore(),
            turnStore = ConversationTurnStore(),
        )
        removeSnapshotListener = service.addSnapshotListener(trace::recordSnapshots)
        removePermissionListener = service.addPermissionListener(trace::recordPermissionRequest)
    }

    override fun tearDown() {
        try {
            trace.dump(testName = name ?: javaClass.simpleName)
            removeSnapshotListener?.invoke()
            removePermissionListener?.invoke()
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

        applySessionUpdate(
            "session-1",
            SessionUpdate.SessionInfoUpdate(title = "Review Session"),
        )
        applySessionUpdate(
            "session-1",
            SessionUpdate.CurrentModeUpdate(SessionModeId("review")),
        )
        applySessionUpdate(
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
        applySessionUpdate(
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
        applySessionUpdate(
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

    private fun applySessionUpdate(sessionId: String, update: SessionUpdate) {
        trace.recordDirectSessionUpdate(sessionId, update)
        service.onSessionUpdate(sessionId, update)
    }

    private fun snapshot(sessionId: String): SessionViewSnapshot =
        requireNotNull(service.snapshots()[sessionId]) { "Expected snapshot for $sessionId" }
}

private class InMemoryProjectSessionCoordinator(
    private val trace: AcpProjectServiceTestTrace,
) : ProjectSessionCoordinator {
    var nextSessionId: String = "session-1"
    var submitPromptBehavior: suspend (String, String, SessionUpdateIngress) -> Unit = { _, _, _ -> }

    override suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): String {
        trace.recordConnectRequest(
            endpointId = endpointId,
            endpointName = endpointName,
            commandLine = commandLine,
            workspaceRoot = workspaceRoot,
        )
        return nextSessionId.also(trace::recordConnectedSession)
    }

    override suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
        trace.recordPromptRequest(sessionId, prompt)
        submitPromptBehavior(sessionId, prompt, tracingIngress(sessionId, ingress))
    }

    override suspend fun cancel(sessionId: String) {
        trace.recordCancellation(sessionId)
    }

    override fun disconnect(sessionId: String) {
        trace.recordDisconnection(sessionId)
    }

    private fun tracingIngress(sessionId: String, delegate: SessionUpdateIngress): SessionUpdateIngress {
        return object : SessionUpdateIngress {
            override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
                trace.recordIngressSessionUpdate(sessionId, update)
                delegate.onSessionUpdate(sessionId, update)
            }

            override fun onPromptFinished(sessionId: String, reason: TurnCompletionReason) {
                trace.recordPromptFinished(sessionId, reason)
                delegate.onPromptFinished(sessionId, reason)
            }

            override fun onPromptFailed(sessionId: String, message: String) {
                trace.recordPromptFailed(sessionId, message)
                delegate.onPromptFailed(sessionId, message)
            }
        }
    }
}

private class AcpProjectServiceTestTrace {
    private val lines = CopyOnWriteArrayList<String>()

    fun recordConnectRequest(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
    ) {
        lines += "connect.request endpointId=$endpointId endpointName=$endpointName workspaceRoot=$workspaceRoot commandLine=$commandLine"
    }

    fun recordConnectedSession(sessionId: String) {
        lines += "connect.result sessionId=$sessionId"
    }

    fun recordPromptRequest(sessionId: String, prompt: String) {
        lines += "prompt.request sessionId=$sessionId prompt=$prompt"
    }

    fun recordIngressSessionUpdate(sessionId: String, update: SessionUpdate) {
        lines += "ingress.update sessionId=$sessionId ${describeSessionUpdate(update)}"
    }

    fun recordDirectSessionUpdate(sessionId: String, update: SessionUpdate) {
        lines += "direct.update sessionId=$sessionId ${describeSessionUpdate(update)}"
    }

    fun recordPromptFinished(sessionId: String, reason: TurnCompletionReason) {
        lines += "prompt.finished sessionId=$sessionId reason=$reason"
    }

    fun recordPromptFailed(sessionId: String, message: String) {
        lines += "prompt.failed sessionId=$sessionId message=$message"
    }

    fun recordCancellation(sessionId: String) {
        lines += "prompt.cancel sessionId=$sessionId"
    }

    fun recordDisconnection(sessionId: String) {
        lines += "session.disconnect sessionId=$sessionId"
    }

    fun recordPermissionRequest(request: PendingPermissionRequest?) {
        lines += if (request == null) {
            "permission.request cleared"
        } else {
            "permission.request toolTitle=${request.toolTitle} options=${request.options}"
        }
    }

    fun recordSnapshots(snapshots: Map<String, SessionViewSnapshot>) {
        if (snapshots.isEmpty()) {
            lines += "snapshot.publish sessions=[]"
            return
        }
        snapshots.toSortedMap().forEach { (sessionId, snapshot) ->
            lines += "snapshot.publish sessionId=$sessionId ${describeSnapshot(snapshot)}"
        }
    }

    fun dump(testName: String) {
        println("===== AcpProjectServiceTest trace: $testName =====")
        if (lines.isEmpty()) {
            println("(no trace)")
        } else {
            lines.forEach(::println)
        }
        println("===== end trace =====")
    }

    private fun describeSessionUpdate(update: SessionUpdate): String {
        return when (update) {
            is SessionUpdate.AgentMessageChunk ->
                "type=AgentMessageChunk text=${describeContent(update.content)} messageId=${update.messageId}"

            is SessionUpdate.AgentThoughtChunk ->
                "type=AgentThoughtChunk text=${describeContent(update.content)} messageId=${update.messageId}"

            is SessionUpdate.UserMessageChunk ->
                "type=UserMessageChunk text=${describeContent(update.content)} messageId=${update.messageId}"

            is SessionUpdate.ToolCall ->
                "type=ToolCall toolCallId=${update.toolCallId} title=${update.title} status=${update.status} kind=${update.kind} rawInput=${update.rawInput} rawOutput=${update.rawOutput} content=${update.content}"

            is SessionUpdate.ToolCallUpdate ->
                "type=ToolCallUpdate toolCallId=${update.toolCallId} title=${update.title} status=${update.status} kind=${update.kind} rawInput=${update.rawInput} rawOutput=${update.rawOutput} content=${update.content}"

            is SessionUpdate.PlanUpdate ->
                "type=PlanUpdate entries=${update.entries}"

            is SessionUpdate.AvailableCommandsUpdate ->
                "type=AvailableCommandsUpdate commands=${update.availableCommands}"

            is SessionUpdate.CurrentModeUpdate ->
                "type=CurrentModeUpdate currentModeId=${update.currentModeId}"

            is SessionUpdate.ConfigOptionUpdate ->
                "type=ConfigOptionUpdate configOptions=${update.configOptions}"

            is SessionUpdate.SessionInfoUpdate ->
                "type=SessionInfoUpdate title=${update.title} updatedAt=${update.updatedAt}"

            is SessionUpdate.UsageUpdate ->
                "type=UsageUpdate used=${update.used} size=${update.size} cost=${update.cost}"

            is SessionUpdate.UnknownSessionUpdate ->
                "type=UnknownSessionUpdate sessionUpdateType=${update.sessionUpdateType} rawJson=${update.rawJson}"
        }
    }

    private fun describeContent(content: ContentBlock): String {
        return when (content) {
            is ContentBlock.Text -> content.text
            is ContentBlock.Image -> "image:${content.mimeType}"
            is ContentBlock.Audio -> "audio:${content.mimeType}"
            is ContentBlock.Resource -> "resource:${content.resource}"
            is ContentBlock.ResourceLink -> "resourceLink:${content.uri}"
        }
    }

    private fun describeSnapshot(snapshot: SessionViewSnapshot): String {
        val timeline = snapshot.visibleTimeline.joinToString(
            prefix = "[",
            postfix = "]",
        ) { item ->
            "{type=${item.itemType},state=${item.displayState},title=${item.title},text=${item.textContent},meta=${item.metadata}}"
        }
        return buildString {
            append("title=")
            append(snapshot.headerState.title)
            append(" connectionStatus=")
            append(snapshot.headerState.connectionStatus)
            append(" sessionStatus=")
            append(snapshot.headerState.sessionStatus)
            append(" currentMode=")
            append(snapshot.headerState.currentMode)
            append(" usageSummary=")
            append(snapshot.headerState.usageSummary)
            append(" composerEnabled=")
            append(snapshot.composerEnabled)
            append(" banner=")
            append(snapshot.bannerState?.text)
            append(" commands=")
            append(snapshot.availableCommands)
            append(" configOptions=")
            append(snapshot.configOptions)
            append(" timeline=")
            append(timeline)
        }
    }
}
