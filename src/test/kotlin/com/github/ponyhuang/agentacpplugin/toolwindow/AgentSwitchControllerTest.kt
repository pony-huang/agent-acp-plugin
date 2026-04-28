package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.github.ponyhuang.agentacpplugin.services.InstallMethod
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
    fun requestSwitchDisconnectsAndConnectsTargetAgentOnce() {
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
                disconnectCurrentAgent = {
                    operations += "disconnect"
                    isConnected = false
                    connectedAgentId = null
                },
                connectAgent = { agent ->
                    operations += "connect:${agent.id}"
                    isConnected = true
                    connectedAgentId = agent.id
                    completed.complete(null)
                }
            )

            controller.requestSwitch(agent("agent-b", "Agent B"))

            completed.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("disconnect", "connect:agent-b"), operations)
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
                disconnectCurrentAgent = {
                    operations += "disconnect"
                    isConnected = false
                    connectedAgentId = null
                },
                connectAgent = { agent ->
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
                listOf("disconnect", "connect:agent-b", "disconnect", "connect:agent-c"),
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
                disconnectCurrentAgent = { operations += "disconnect" },
                connectAgent = { agent -> operations += "connect:${agent.id}" }
            )

            controller.requestSwitch(agent("agent-a", "Agent A"))

            Thread.sleep(100)
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
