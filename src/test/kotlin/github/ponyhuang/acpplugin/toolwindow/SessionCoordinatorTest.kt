package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.services.InstallMethod
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCoordinatorTest {

    @Test
    fun connectCreatesSessionWhenDisconnected() = runBlocking {
        val operations = FakeSessionOperations()
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            coordinator.connect(agent("agent-a"), "E:/project")

            assertEquals(listOf("create:agent-a:E:/project"), operations.calls)
            assertEquals("agent-a", coordinator.currentAgentId.value)
            assertEquals(listOf("connected:Agent A"), notifier.events)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun connectReplacesSessionWhenConnected() = runBlocking {
        val operations = FakeSessionOperations(currentAgentId = "agent-a", connected = true)
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            coordinator.connect(agent("agent-b"), "E:/project")

            assertEquals(listOf("replace:agent-b:E:/project"), operations.calls)
            assertEquals("agent-b", coordinator.currentAgentId.value)
            assertEquals(listOf("connected:Agent B"), notifier.events)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun requestSwitchUsesCwdProviderAndReplacesCurrentSession() {
        val operations = FakeSessionOperations(currentAgentId = "agent-a", connected = true)
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(
            sessionOperations = operations,
            notifier = notifier,
            cwdProvider = { "E:/switch-cwd" }
        )
        try {
            coordinator.requestSwitch(agent("agent-b"))

            operations.awaitCall()
            while (coordinator.isSwitching.value) {
                Thread.sleep(10)
            }
            assertEquals(listOf("replace:agent-b:E:/switch-cwd"), operations.calls)
            assertEquals("agent-b", coordinator.currentAgentId.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun requestSwitchIgnoresAlreadyConnectedAgent() {
        val operations = FakeSessionOperations(currentAgentId = "agent-a", connected = true)
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            val traceId = coordinator.requestSwitch(agent("agent-a"))

            Thread.sleep(100)
            assertNull(traceId)
            assertEquals(emptyList<String>(), operations.calls)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun disconnectClearsCurrentAgentAndNotifies() = runBlocking {
        val operations = FakeSessionOperations(currentAgentId = "agent-a", connected = true)
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            coordinator.disconnect()

            assertEquals(listOf("disconnect"), operations.calls)
            assertNull(coordinator.currentAgentId.value)
            assertEquals(listOf("disconnected"), notifier.events)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun cancelDelegatesToSessionOperations() = runBlocking {
        val operations = FakeSessionOperations()
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            coordinator.cancel()

            assertEquals(listOf("cancel"), operations.calls)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun createNewSessionWithNoAgentShowsWarning() {
        val operations = FakeSessionOperations()
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            coordinator.createNewSession(null, "E:/project")

            assertEquals(emptyList<String>(), operations.calls)
            assertEquals(listOf("no-agent"), notifier.events)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun createNewSessionDelegatesAndNotifies() {
        val operations = FakeSessionOperations()
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        try {
            coordinator.createNewSession(agent("agent-a"), "E:/project")

            operations.awaitCall()
            assertEquals(listOf("create:agent-a:E:/project"), operations.calls)
            assertEquals("agent-a", coordinator.currentAgentId.value)
            assertEquals(listOf("new:Agent A"), notifier.events)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun resumeSessionDelegatesAndNotifies() {
        val operations = FakeSessionOperations()
        val notifier = FakeNotificationSink()
        val coordinator = SessionCoordinator(operations, notifier)
        val session = AcpSessionService.SessionListItem(
            sessionId = "session-1",
            title = "Session",
            cwd = "E:/project",
            updatedAtMillis = null
        )
        try {
            coordinator.resumeSession(agent("agent-a"), "E:/project", session)

            operations.awaitCall()
            assertEquals(listOf("resume:session-1:agent-a:E:/project"), operations.calls)
            assertEquals("agent-a", coordinator.currentAgentId.value)
            assertEquals(listOf("resumed:session-1"), notifier.events)
        } finally {
            coordinator.dispose()
        }
    }

    private class FakeSessionOperations(
        currentAgentId: String? = null,
        connected: Boolean = false,
    ) : SessionLifecycleOperations {
        override val isConnected = MutableStateFlow(connected)
        override val isLoading = MutableStateFlow(false)
        val calls = CopyOnWriteArrayList<String>()
        private val firstCall = CompletableFuture<Void>()
        private var currentAgentId: String? = currentAgentId

        override fun currentAgentId(): String? = currentAgentId

        override suspend fun createSession(agent: AgentRegistry.InstalledAgent, cwd: String) {
            calls += "create:${agent.id}:$cwd"
            currentAgentId = agent.id
            isConnected.value = true
            completeFirstCall()
        }

        override suspend fun replaceSession(agent: AgentRegistry.InstalledAgent, cwd: String) {
            calls += "replace:${agent.id}:$cwd"
            currentAgentId = agent.id
            isConnected.value = true
            completeFirstCall()
        }

        override suspend fun resumeSession(sessionId: String, agent: AgentRegistry.InstalledAgent, cwd: String) {
            calls += "resume:$sessionId:${agent.id}:$cwd"
            currentAgentId = agent.id
            isConnected.value = true
            completeFirstCall()
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            currentAgentId = null
            isConnected.value = false
            completeFirstCall()
        }

        override suspend fun cancel() {
            calls += "cancel"
            completeFirstCall()
        }

        fun awaitCall() {
            firstCall.get(2, TimeUnit.SECONDS)
        }

        private fun completeFirstCall() {
            firstCall.complete(null)
        }
    }

    private class FakeNotificationSink : SessionNotificationSink {
        val events = CopyOnWriteArrayList<String>()

        override fun notifyConnected(agentDisplayName: String) {
            events += "connected:$agentDisplayName"
        }

        override fun notifyDisconnected() {
            events += "disconnected"
        }

        override fun notifyNoAgentSelected() {
            events += "no-agent"
        }

        override fun notifyFailedListSessions(content: String) {
            events += "failed-list:$content"
        }

        override fun notifyNewSessionCreated(agentDisplayName: String) {
            events += "new:$agentDisplayName"
        }

        override fun notifySessionResumed(session: AcpSessionService.SessionListItem) {
            events += "resumed:${session.sessionId}"
        }

        override fun notifyError(groupTitle: String, title: String, content: String) {
            events += "error:$title:$content"
        }
    }

    private fun agent(id: String) = AgentRegistry.InstalledAgent(
        registryAgentId = id,
        id = id,
        displayName = when (id) {
            "agent-a" -> "Agent A"
            "agent-b" -> "Agent B"
            else -> id
        },
        description = "Description",
        version = "1.0.0",
        iconPath = null,
        installMethod = InstallMethod.NPX,
        sourceLabel = "Official",
        command = "npx",
        args = emptyList(),
        env = emptyMap(),
        isLegacy = false,
    )
}
