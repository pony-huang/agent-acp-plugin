package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.render.ContentBlockRenderer
import com.github.ponyhuang.agentacpplugin.services.session.SessionRegistry
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import junit.framework.TestCase
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class AcpClientFacadeLiveIntegrationTest : TestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var registry: SessionRegistry
    private lateinit var facade: AcpClientFacade

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        registry = SessionRegistry()
        facade = AcpClientFacade(scope, registry)
    }

    override fun tearDown() {
        try {
            registry.all().forEach { facade.disconnect(it.sessionId.toString()) }
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testPromptRoundTripAgainstRealClaudeAgentAcp() = runBlocking {
        if (!shouldRunLiveTest()) {
            println("Skipping live ACP test. Run directly from IntelliJ or set ACP_REAL_AGENT_TESTS=true / -Dagentacp.realAgentTests=true.")
            return@runBlocking
        }

        val ingress = RecordingIngress()
        val endpointId = "endpoint-live-test"
        val endpointName = "claude-agent-acp"
        val workspaceRoot = Path.of("").toAbsolutePath()
        val timeoutMillis = liveTestTimeoutMillis()
        val prompt = liveTestPrompt()

        val connected = withTimeout(timeoutMillis.milliseconds) {
            facade.connect(
                endpointId = endpointId,
                endpointName = endpointName,
                commandLine = liveTestCommandLine(),
                workspaceRoot = workspaceRoot,
                ingress = ingress,
                permissionRequestHandler = PermissionRequestHandler(),
            )
        }

        try {
            withTimeout(timeoutMillis.milliseconds) {
                facade.prompt(connected.sessionId.toString(), prompt, ingress)
            }

            val renderedAssistantOutput = ingress.updates
                .filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .joinToString(separator = "") { renderer.render(it.content) }

            assertTrue(
                "Expected at least one AgentMessageChunk from the real ACP agent. Updates: ${ingress.updates.map { it::class.simpleName }}",
                ingress.updates.any { it is SessionUpdate.AgentMessageChunk },
            )
            assertEquals(
                "Expected the prompt to finish normally.",
                TurnCompletionReason.END_TURN,
                ingress.completionReasons.singleOrNull(),
            )
            assertTrue(
                "Expected assistant output to include token ACP_OK. Output was: $renderedAssistantOutput",
                renderedAssistantOutput.contains("ACP_OK"),
            )
        } finally {
            facade.disconnect(connected.sessionId.toString())
        }
    }

    private fun shouldRunLiveTest(): Boolean {
        val systemProperty = System.getProperty("agentacp.realAgentTests")
        val envValue = System.getenv("ACP_REAL_AGENT_TESTS")
        return systemProperty.equals("true", ignoreCase = true) ||
            envValue.equals("true", ignoreCase = true) ||
            isDirectIntelliJRun()
    }

    private fun isDirectIntelliJRun(): Boolean {
        val command = System.getProperty("sun.java.command").orEmpty()
        if (!command.contains("com.intellij.rt.junit.JUnitStarter")) {
            return false
        }
        return command.contains(javaClass.name) || command.contains("testPromptRoundTripAgainstRealClaudeAgentAcp")
    }

    private fun liveTestCommandLine(): String {
        return System.getProperty("agentacp.realAgentCommand")
            ?: System.getenv("ACP_REAL_AGENT_COMMAND")
            ?: "npx @agentclientprotocol/claude-agent-acp"
    }

    private fun liveTestPrompt(): String {
        return System.getProperty("agentacp.realAgentPrompt")
            ?: System.getenv("ACP_REAL_AGENT_PROMPT")
            ?: "Reply with exactly ACP_OK and nothing else."
    }

    private fun liveTestTimeoutMillis(): Long {
        val configured = System.getProperty("agentacp.realAgentTimeoutMillis")
            ?: System.getenv("ACP_REAL_AGENT_TIMEOUT_MILLIS")
        return configured?.toLongOrNull() ?: 120_000L
    }

    private companion object {
        private val renderer = ContentBlockRenderer()
    }
}

private class RecordingIngress : SessionUpdateIngress {
    val updates = CopyOnWriteArrayList<SessionUpdate>()
    val completionReasons = CopyOnWriteArrayList<TurnCompletionReason>()
    val failures = CopyOnWriteArrayList<String>()

    override fun onSessionUpdate(sessionId: String, update: SessionUpdate) {
        updates += update
    }

    override fun onPromptFinished(sessionId: String, reason: TurnCompletionReason) {
        completionReasons += reason
    }

    override fun onPromptFailed(sessionId: String, message: String) {
        failures += message
    }
}
