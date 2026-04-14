package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.github.ponyhuang.agentacpplugin.services.session.RegisteredSession
import com.github.ponyhuang.agentacpplugin.services.session.SessionRegistry
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

class AcpClientFacade(
    private val scope: CoroutineScope,
    private val registry: SessionRegistry,
    private val processLauncher: AcpAgentProcessLauncher = AcpAgentProcessLauncher(),
    private val transportFactory: AcpTransportFactory = AcpTransportFactory(),
) {
    private val logger = Logger.getInstance(AcpClientFacade::class.java)

    @OptIn(UnstableApi::class)
    suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): RegisteredSession {
        val launchedProcess = processLauncher.launch(commandLine, workspaceRoot)
        val protocol = Protocol(scope, transportFactory.create(scope, launchedProcess))
        val client = Client(protocol)
        protocol.start()
        client.initialize(
            ClientInfo(
                capabilities = ClientCapabilities(
                    fs = FileSystemCapability(readTextFile = true, writeTextFile = true),
                    terminal = true,
                ),
                implementation = Implementation(name = "ACP Chat", version = "0.1.0"),
            ),
        )
        val session = client.newSession(
            SessionCreationParameters(
                cwd = workspaceRoot.toString(),
                mcpServers = emptyList(),
            ),
        ) { sessionId, _ ->
            IdeClientSessionOperations(
                sessionId = sessionId.toString(),
                fileSystemAdapter = FileSystemExtensionAdapter(workspaceRoot),
                terminalAdapter = TerminalExtensionAdapter(workspaceRoot),
                permissionRequestHandler = permissionRequestHandler,
            ) { update ->
                ingress.onSessionUpdate(sessionId.toString(), update)
            }
        }
        val registered = RegisteredSession(
            endpointId = endpointId,
            endpointName = endpointName,
            sessionId = session.sessionId,
            client = client,
            protocol = protocol,
            session = session,
            processHandle = launchedProcess,
        )
        registry.register(registered)
        return registered
    }

    suspend fun prompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
        val registered = registry.get(sessionId) ?: error("Session $sessionId not found")
        registered.session.prompt(listOf(ContentBlock.Text(prompt))).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> {
                    AcpProtocolDebugLogger.logSessionUpdate(logger, "prompt-stream", sessionId, event.update)
                    ingress.onSessionUpdate(sessionId, event.update)
                }

                is Event.PromptResponseEvent -> {
                    val reason = event.response.stopReason.toReason()
                    AcpProtocolDebugLogger.logPromptFinished(logger, "prompt-stream", sessionId, reason)
                    ingress.onPromptFinished(sessionId, reason)
                }
            }
        }
    }

    suspend fun cancel(sessionId: String) {
        registry.get(sessionId)?.session?.cancel()
    }

    fun disconnect(sessionId: String) {
        registry.remove(sessionId)?.processHandle?.close()
    }

    private fun StopReason.toReason(): TurnCompletionReason {
        return when (this) {
            StopReason.END_TURN -> TurnCompletionReason.END_TURN
            StopReason.CANCELLED -> TurnCompletionReason.CANCELLED
            StopReason.MAX_TOKENS,
            StopReason.MAX_TURN_REQUESTS,
            StopReason.REFUSAL,
            -> TurnCompletionReason.UNKNOWN
        }
    }
}
