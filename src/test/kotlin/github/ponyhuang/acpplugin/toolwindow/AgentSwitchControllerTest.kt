package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.services.InstallMethod
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSwitchControllerTest {

    @Test
    fun requestSwitchConnectsTargetAgentOnceWithoutForegroundDisconnect() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val operations = CopyOnWriteArrayList<String>()
            var isConnected = true
            var connectedAgentId: String? = "agent-a"
            val completed = CompletableFuture<Void>()
            val controller = AgentSwitchController(
                scope = scope,
                isConnected = { isConnected },
                currentConnectedAgentId = { connectedAgentId },
                connectAgent = { request ->
                    val agent = request.agent
                    operations += "connect:${agent.id}"
                    isConnected = true
                    connectedAgentId = agent.id
                    completed.complete(null)
                }
            )

            val traceId = controller.requestSwitch(agent("agent-b", "Agent B"))

            completed.get(2, TimeUnit.SECONDS)
            while (controller.isSwitching.value) {
                Thread.sleep(10)
            }
            assertTrue(traceId?.startsWith("switch-") == true)
            assertEquals(listOf("connect:agent-b"), operations)
            assertTrue(!controller.isSwitching.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestSwitchKeepsLatestQueuedAgentWhileCurrentSwitchIsRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val operations = CopyOnWriteArrayList<String>()
            var isConnected = true
            var connectedAgentId: String? = "agent-a"
            val releaseFirstConnect = CompletableFuture<Void>()
            val completed = CompletableFuture<Void>()
            var connectCount = 0
            val controller = AgentSwitchController(
                scope = scope,
                isConnected = { isConnected },
                currentConnectedAgentId = { connectedAgentId },
                connectAgent = { request ->
                    val agent = request.agent
                    operations += "connect:${agent.id}"
                    connectCount += 1
                    if (connectCount == 1) {
                        releaseFirstConnect.get(2, TimeUnit.SECONDS)
                    }
                    isConnected = true
                    connectedAgentId = agent.id
                    if (agent.id == "agent-c") {
                        completed.complete(null)
                    }
                }
            )

            controller.requestSwitch(agent("agent-b", "Agent B"))
            while (operations.isEmpty()) {
                delay(10)
            }
            controller.requestSwitch(agent("agent-c", "Agent C"))
            releaseFirstConnect.complete(null)

            completed.get(2, TimeUnit.SECONDS)
            assertEquals(
                listOf("connect:agent-b", "connect:agent-c"),
                operations
            )
            assertEquals("agent-c", connectedAgentId)
            assertTrue(!controller.isSwitching.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestSwitchDoesNothingForAlreadyConnectedAgent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val operations = CopyOnWriteArrayList<String>()
            val controller = AgentSwitchController(
                scope = scope,
                isConnected = { true },
                currentConnectedAgentId = { "agent-a" },
                connectAgent = { request -> operations += "connect:${request.agent.id}" }
            )

            val traceId = controller.requestSwitch(agent("agent-a", "Agent A"))

            Thread.sleep(100)
            assertEquals(null, traceId)
            assertTrue(operations.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun agent(id: String, displayName: String) = AgentRegistry.InstalledAgent(
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
        isLegacy = false,
    )
}
