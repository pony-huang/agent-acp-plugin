package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * @author: pony
 */
private const val STDERR_LABEL = "STDERR:"

fun createProcessStdioTransport(
    coroutineScope: CoroutineScope,
    envs: List<String>,
    command: List<String>
): Transport {
    val processBuilder = ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)

    // Parse and apply environment variables from "KEY=VALUE" format
    applyEnvironmentVariables(processBuilder, envs)

    val process = processBuilder.start()
    return StdioTransport(
        parentScope = coroutineScope,
        ioDispatcher = Dispatchers.IO,
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered()
    )
}

internal fun projectSessionRoot(project: Project): String {
    return project.basePath?.let { Paths.get(it).toAbsolutePath().normalize().absolutePathString() }
        ?: Paths.get("").toAbsolutePath().normalize().absolutePathString()
}

private fun applyEnvironmentVariables(processBuilder: ProcessBuilder, envs: List<String>) {
    if (envs.isEmpty()) {
        return
    }

    val environment = processBuilder.environment()
    envs.forEach { envVar ->
        val parts = envVar.split("=", limit = 2)
        if (parts.size == 2) {
            environment[parts[0]] = parts[1]
        }
    }
}

private val logger: Logger = Logger.getInstance(DefaultClientSessionOperations::class.java)

class DefaultClientSessionOperations(
    val sessionUpdateSink: suspend (SessionUpdate) -> Unit,
    val permissionRequestSink: suspend (SessionUpdate.ToolCallUpdate, List<PermissionOption>, JsonElement?) -> RequestPermissionResponse = { toolCall, permissions, meta ->
        autoApprovePermissions(toolCall, permissions, meta)
    },
) : ClientSessionOperations {
    private val activeTerminals = ConcurrentHashMap<String, Process>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return permissionRequestSink(toolCall, permissions, _meta)
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        sessionUpdateSink.invoke(notification)
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        val content = Paths.get(path).readText()
        return ReadTextFileResponse(content)
    }

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse {
        val targetPath = Paths.get(path)
        targetPath.parent?.createDirectories()
        targetPath.writeText(content)
        return WriteTextFileResponse()
    }

    override suspend fun terminalCreate(
        command: String, args: List<String>,
        cwd: String?, env: List<EnvVariable>,
        outputByteLimit: ULong?, _meta: JsonElement?,
    ): CreateTerminalResponse {
        val processBuilder = if (System.getProperty("os.name").lowercase().contains("windows")) {
            // Windows: 将 bash -c 命令转换为 cmd.exe /c
            if (command == "bash" && args.firstOrNull() == "-c") {
                ProcessBuilder(listOf("cmd.exe", "/c") + args.drop(1))
            } else {
                ProcessBuilder(listOf(command) + args)
            }
        } else {
            // Unix-like: 直接执行
            ProcessBuilder(listOf(command) + args)
        }
        cwd?.let { processBuilder.directory(File(it)) }
        processBuilder.environment().putAll(env.associate { it.name to it.value })

        val process = processBuilder.start()
        val terminalId = UUID.randomUUID().toString()
        activeTerminals[terminalId] = process

        return CreateTerminalResponse(terminalId)
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
        val process = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val output = buildString {
            append(stdout)
            if (stderr.isNotEmpty()) {
                appendLine()
                append(STDERR_LABEL)
                appendLine()
                append(stderr)
            }
        }

        return TerminalOutputResponse(output, truncated = false)
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
        activeTerminals.remove(terminalId)
        return ReleaseTerminalResponse()
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse {
        val process = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
        val exitCode = process.waitFor()
        return WaitForTerminalExitResponse(exitCode.toUInt())
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
        val process = activeTerminals[terminalId]
        process?.destroy()
        return KillTerminalCommandResponse()
    }

    private companion object {
        fun autoApprovePermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            meta: JsonElement?,
        ): RequestPermissionResponse {
            logger.info("[PermissionRequest] Agent requested permissions for tool call: ${toolCall.title}")
            permissions.forEachIndexed { index, permission ->
                logger.info("[PermissionRequest]   ${index + 1}. ${permission.name} (${permission.kind})")
            }

            val selectedOption = permissions.firstOrNull {
                it.kind == PermissionOptionKind.ALLOW_ALWAYS || it.kind == PermissionOptionKind.ALLOW_ONCE
            } ?: permissions.firstOrNull()

            return if (selectedOption != null) {
                logger.info("[PermissionRequest] Auto-selecting option: ${selectedOption.name}")
                RequestPermissionResponse(
                    RequestPermissionOutcome.Selected(selectedOption.optionId),
                    meta
                )
            } else {
                logger.info("[PermissionRequest] No options available, cancelling request")
                RequestPermissionResponse(
                    RequestPermissionOutcome.Cancelled,
                    meta
                )
            }
        }
    }
}


class AcpAgentClient(
    val coroutineScope: CoroutineScope,
    val project: Project,
    val cmd: List<String>,
    val envs: List<String>,
    val sessionUpdateSink: suspend (SessionUpdate) -> Unit,
    val permissionRequestSink: suspend (SessionUpdate.ToolCallUpdate, List<PermissionOption>, JsonElement?) -> RequestPermissionResponse,
) {
    private var client: Client? = null
    private var protocol: Protocol? = null
    private val sessionCreationParameters: SessionCreationParameters
        get() = SessionCreationParameters(projectSessionRoot(project), emptyList())

    @OptIn(UnstableApi::class)
    suspend fun connect(): AgentInfo? {
        val transport = createProcessStdioTransport(coroutineScope, envs, cmd)
        val protocol = Protocol(coroutineScope, transport)
        this.protocol = protocol
        client = Client(protocol)
        protocol.start()
        return client?.initialize(
            ClientInfo(capabilities = CLIENT_CAPABILITIES)
        )
    }

    suspend fun newSession(): ClientSession? {
        return client?.newSession(
            sessionCreationParameters
        ) { _, _ -> DefaultClientSessionOperations(sessionUpdateSink, permissionRequestSink) }
    }

    suspend fun loadSession(sessionId: SessionId): ClientSession? {
        return client?.loadSession(
            sessionId,
            sessionCreationParameters
        ) { _, _ -> DefaultClientSessionOperations(sessionUpdateSink, permissionRequestSink) }
    }

    @OptIn(UnstableApi::class)
    suspend fun resumeSession(sessionId: SessionId): ClientSession? {
        return client?.resumeSession(
            sessionId,
            sessionCreationParameters
        ) { _, _ -> DefaultClientSessionOperations(sessionUpdateSink, permissionRequestSink) }
    }

    @OptIn(UnstableApi::class)
    suspend fun listSessions(cwd: String? = projectSessionRoot(project)): List<SessionInfo> {
        return client?.listSessions(cwd = cwd)?.toList().orEmpty()
    }

    fun close() {
        protocol?.close()
        protocol = null
        client = null
    }

    private companion object {
        @OptIn(UnstableApi::class)
        val CLIENT_CAPABILITIES = ClientCapabilities(
            fs = FileSystemCapability(
                readTextFile = true,
                writeTextFile = true
            ),
            terminal = true
        )
    }
}
