package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

private val logger = Logger.getInstance(ProcessAcpRuntimeConnector::class.java)

internal object ProcessAcpRuntimeConnector : AcpRuntimeConnector {
    @OptIn(UnstableApi::class)
    override suspend fun connect(
        project: Project,
        scope: CoroutineScope,
        descriptor: AcpAgentDescriptor,
        eventSink: suspend (AcpServiceEvent) -> Unit,
    ): AcpRuntimeConnection {
        require(descriptor.command.isNotBlank()) { "ACP command must not be empty" }

        val processHandle = createProcessStdioTransport(scope, project, descriptor)
        try {
            val protocol = Protocol(scope, processHandle.transport)
            val client = Client(protocol)
            protocol.start()

            val agentInfo = client.initialize(
                ClientInfo(
                    capabilities = ClientCapabilities(
                        fs = FileSystemCapability(
                            readTextFile = true,
                            writeTextFile = true,
                        ),
                        terminal = true,
                    ),
                )
            )

            val operations = TerminalClientSessionOperations(
                project = project,
                coroutineScope = scope,
                eventSink = eventSink,
            )

            val session = client.newSession(
                SessionCreationParameters(projectSessionRoot(project), emptyList())
            ) { _, _ -> operations }

            return ProcessAcpRuntimeConnection(
                descriptor = descriptor,
                processHandle = processHandle,
                protocol = protocol,
                client = client,
                agentInfo = agentInfo,
                session = session,
                operations = operations,
            )
        } catch (t: Throwable) {
            processHandle.close()
            throw t
        }
    }
}

internal data class ProcessAcpRuntimeConnection(
    override val descriptor: AcpAgentDescriptor,
    private val processHandle: ProcessTransportHandle,
    private val protocol: Protocol,
    override val client: Client,
    override val agentInfo: AgentInfo,
    override val session: ClientSession,
    private val operations: TerminalClientSessionOperations,
) : AcpRuntimeConnection {

    @OptIn(UnstableApi::class)
    override suspend fun close() {
        runCatching {
            operations.close()
        }.onFailure {
            logger.warn("Failed to close ACP terminal operations", it)
        }

        runCatching {
            if (agentInfo.capabilities.sessionCapabilities.close != null) {
                session.close()
            } else {
                session.cancel()
            }
        }.onFailure {
            logger.warn("Failed to close ACP session ${session.sessionId}", it)
        }

        runCatching {
            protocol.close()
        }.onFailure {
            logger.warn("Failed to close ACP protocol for session ${session.sessionId}", it)
        }

        processHandle.close()
    }
}

internal data class ProcessTransportHandle(
    val process: Process,
    val transport: Transport,
    private val stderrJob: Job,
) {
    suspend fun close() {
        transport.close()
        withContext(NonCancellable) {
            runCatching { process.errorStream.close() }
            stderrJob.cancel()
        }
        if (process.isAlive) {
            process.destroy()
            withContext(Dispatchers.IO) {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        }
    }
}

internal fun createProcessStdioTransport(
    coroutineScope: CoroutineScope,
    project: Project,
    descriptor: AcpAgentDescriptor,
): ProcessTransportHandle {
    val processBuilder = ProcessBuilder(descriptor.commandLine)
        .directory(File(projectSessionRoot(project)))
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
    descriptor.env.forEach { (key, value) ->
        processBuilder.environment()[key] = value
    }
    val process = processBuilder.start()

    val stderrJob = coroutineScope.launch(Dispatchers.IO) {
        process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                logger.warn("[ACP stderr] $line")
            }
        }
    }

    val stdin = process.outputStream.asSink().buffered()
    val stdout = process.inputStream.asSource().buffered()
    val transport = StdioTransport(
        parentScope = coroutineScope,
        ioDispatcher = Dispatchers.IO,
        input = stdout,
        output = stdin,
        name = "ACP:${descriptor.id}",
    )
    return ProcessTransportHandle(process, transport, stderrJob)
}

internal fun projectSessionRoot(project: Project): String {
    return project.basePath?.let { Paths.get(it).toAbsolutePath().normalize().absolutePathString() }
        ?: Paths.get("").toAbsolutePath().normalize().absolutePathString()
}
