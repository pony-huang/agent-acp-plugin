package com.agentclientprotocol.samples

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import com.github.ponyhuang.agentacpplugin.services.AcpProjectService
import com.github.ponyhuang.agentacpplugin.services.TerminalClientSessionOperations
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

private val logger = Logger.getInstance(AcpProjectService::class.java)

fun createProcessStdioTransport(coroutineScope: CoroutineScope, vararg command: String): Transport {
    val process = ProcessBuilder(*command)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    val stdin = process.outputStream.asSink().buffered()
    val stdout = process.inputStream.asSource().buffered()
    return StdioTransport(
        parentScope = coroutineScope,
        ioDispatcher = Dispatchers.IO,
        input = stdout,
        output = stdin
    )
}


suspend fun CoroutineScope.runTerminalClient(transport: Transport) {
    // Create client-side connection
    val protocol = Protocol(this, transport)
    val client = Client(
        protocol
    )

    logger.info("Starting Gemini agent process...")

    // Connect to agent and start transport
    protocol.start()

    logger.info("Connected to Gemini agent, initializing...")

    val agentInfo = client.initialize(
        ClientInfo(
            capabilities = ClientCapabilities(
                fs = FileSystemCapability(
                    readTextFile = true,
                    writeTextFile = true
                ),
                terminal = true
            )
        )
    )
    println("Agent info: $agentInfo")

    println()

    // Create a session
    val session = client.newSession(
        SessionCreationParameters(Paths.get("").absolutePathString(), emptyList())
    ) { session, _ -> TerminalClientSessionOperations() }

    println("=== Session created: ${session.sessionId} ===")
    println("Type your messages below. Use 'exit', 'quit', or Ctrl+C to stop.")
    println("=".repeat(60))
    println()

    try {
        // Start interactive chat loop
        while (true) {
            print("You: ")
            val userInput = readLine()

            // Check for exit conditions
            if (userInput == null || userInput.lowercase() in listOf("exit", "quit", "bye")) {
                println("\n=== Goodbye! ===")
                break
            }

            // Skip empty inputs
            if (userInput.isBlank()) {
                continue
            }

            try {
                session.prompt(listOf(ContentBlock.Text(userInput.trim()))).collect { event ->
                    when (event) {
                        is Event.SessionUpdateEvent -> {
                            event.update.render()
                        }

                        is Event.PromptResponseEvent -> {
                            when (event.response.stopReason) {
                                StopReason.END_TURN -> {
                                    // Normal completion - no action needed
                                }

                                StopReason.MAX_TOKENS -> {
                                    println("\n[Response truncated due to token limit]")
                                }

                                StopReason.MAX_TURN_REQUESTS -> {
                                    println("\n[Turn limit reached]")
                                }

                                StopReason.REFUSAL -> {
                                    println("\n[Agent declined to respond]")
                                }

                                StopReason.CANCELLED -> {
                                    println("\n[Response was cancelled]")
                                }
                            }
                        }
                    }
                }



                println() // Extra newline for readability

            } catch (e: Exception) {
                println("\n[Error: ${e.message}]")
                logger.error("Error during chat interaction")
            }
        }

    } catch (e: Exception) {
        logger.error("Client error occurred")
        println("Error: ${e.message}")
        e.printStackTrace()
    } finally {
        logger.info("Gemini ACP client shutting down")
    }
}