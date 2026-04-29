package github.ponyhuang.acpplugin.services

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
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val PROTOCOL_CLOSE_TIMEOUT_MS = 5_000L
private const val PROCESS_EXIT_TIMEOUT_MS = 2_000L

data class ProcessTransportHandle(
    val transport: Transport,
    val process: Process,
)

fun createProcessStdioTransport(
    coroutineScope: CoroutineScope,
    envs: List<String>,
    command: List<String>
): ProcessTransportHandle {
    val processBuilder = ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)

    // Parse and apply environment variables from "KEY=VALUE" format
    applyEnvironmentVariables(processBuilder, envs)

    val process = processBuilder.start()
    return ProcessTransportHandle(
        transport = StdioTransport(
            parentScope = coroutineScope,
            ioDispatcher = Dispatchers.IO,
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered()
        ),
        process = process
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
//        val processBuilder = if (System.getProperty("os.name").lowercase().contains("windows")) {
//            // Windows: 将 bash -c 命令转换为 cmd.exe /c
//            if (command == "bash" && args.firstOrNull() == "-c") {
//                ProcessBuilder(listOf("cmd.exe", "/c") + args.drop(1))
//            } else {
//                ProcessBuilder(listOf(command) + args)
//            }
//        } else {
//            // Unix-like: 直接执行
//            ProcessBuilder(listOf(command) + args)
//        }
//        cwd?.let { processBuilder.directory(File(it)) }
//        processBuilder.environment().putAll(env.associate { it.name to it.value })
//
//        val process = processBuilder.start()
//        val terminalId = UUID.randomUUID().toString()
//        activeTerminals[terminalId] = process
//
//        return CreateTerminalResponse(terminalId)
        return CreateTerminalResponse("terminalId")
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
//        val process = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
//        val stdout = process.inputStream.bufferedReader().readText()
//        val stderr = process.errorStream.bufferedReader().readText()
//        val output = buildString {
//            append(stdout)
//            if (stderr.isNotEmpty()) {
//                appendLine()
//                append(STDERR_LABEL)
//                appendLine()
//                append(stderr)
//            }
//        }
//
//        return TerminalOutputResponse(output, truncated = false)
        return TerminalOutputResponse("", truncated = false)
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
//        activeTerminals.remove(terminalId)
        return ReleaseTerminalResponse()
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse {
//        val process = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
//        val exitCode = process.waitFor()
//        return WaitForTerminalExitResponse(exitCode.toUInt())
        return WaitForTerminalExitResponse()
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
//        val process = activeTerminals[terminalId]
//        process?.destroy()
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
    private var process: Process? = null
    private val traceLabel = cmd.joinToString(" ")
    private val sessionCreationParameters: SessionCreationParameters
        get() = SessionCreationParameters(projectSessionRoot(project), emptyList())

    @OptIn(UnstableApi::class)
    suspend fun connect(): AgentInfo? {
        logger.info("[AgentClient] connect start: cmd=$traceLabel, envCount=${envs.size}")
        val transportHandle = createProcessStdioTransport(coroutineScope, envs, cmd)
        process = transportHandle.process
        logger.info("[AgentClient] process started: cmd=$traceLabel, pid=${process?.pid() ?: -1}")
        val protocol = Protocol(coroutineScope, transportHandle.transport)
        this.protocol = protocol
        client = Client(protocol)
        protocol.start()
        val info = client?.initialize(
            ClientInfo(capabilities = CLIENT_CAPABILITIES)
        )
        logger.info("[AgentClient] connect end: cmd=$traceLabel, initialized=${info != null}, implementation=${info?.implementation?.name ?: "<none>"}")
        return info
    }

    suspend fun newSession(): ClientSession? {
        logger.info("[AgentClient] newSession start: cmd=$traceLabel")
        val session = client?.newSession(
            sessionCreationParameters
        ) { _, _ -> DefaultClientSessionOperations(sessionUpdateSink, permissionRequestSink) }
        logger.info("[AgentClient] newSession end: cmd=$traceLabel, sessionId=${session?.sessionId?.value ?: "<none>"}")
        return session
    }

    suspend fun loadSession(sessionId: SessionId): ClientSession? {
        return client?.loadSession(
            sessionId,
            sessionCreationParameters
        ) { _, _ -> DefaultClientSessionOperations(sessionUpdateSink, permissionRequestSink) }
    }

    @OptIn(UnstableApi::class)
    suspend fun resumeSession(sessionId: SessionId): ClientSession? {
        logger.info("[AgentClient] resumeSession start: cmd=$traceLabel, sessionId=${sessionId.value}")
        val session = client?.resumeSession(
            sessionId,
            sessionCreationParameters
        ) { _, _ -> DefaultClientSessionOperations(sessionUpdateSink, permissionRequestSink) }
        logger.info("[AgentClient] resumeSession end: cmd=$traceLabel, resumedSessionId=${session?.sessionId?.value ?: "<none>"}")
        return session
    }

    @OptIn(UnstableApi::class)
    suspend fun listSessions(cwd: String? = projectSessionRoot(project)): List<SessionInfo> {
        return client?.listSessions(cwd = cwd)?.toList().orEmpty()
    }

    fun close() {
        val activeProcess = process
        logger.info(
            "[AgentClient] close start: cmd=$traceLabel, pid=${activeProcess?.pid() ?: -1}, " +
                "alive=${activeProcess?.isAlive ?: false}"
        )
        closeProtocolWithTimeout()
        terminateProcess(activeProcess)
        protocol = null
        client = null
        process = null
        logger.info("[AgentClient] close end: cmd=$traceLabel")
    }

    private fun closeProtocolWithTimeout() {
        val activeProtocol = protocol ?: return
        val completion = CountDownLatch(1)
        var failure: Throwable? = null
        val thread = Thread(
            {
                try {
                    logger.info("[AgentClient] protocol.close start: cmd=$traceLabel")
                    activeProtocol.close()
                    logger.info("[AgentClient] protocol.close end: cmd=$traceLabel")
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    completion.countDown()
                }
            },
            "acp-protocol-close-${traceLabel.hashCode()}"
        ).apply {
            isDaemon = true
        }
        thread.start()
        val completed = completion.await(PROTOCOL_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        when {
            completed && failure == null -> Unit
            completed && failure != null -> logger.warn("[AgentClient] protocol.close failed: cmd=$traceLabel", failure)
            else -> logger.warn("[AgentClient] protocol.close timed out after $PROTOCOL_CLOSE_TIMEOUT_MS ms: cmd=$traceLabel")
        }
    }

    private fun terminateProcess(target: Process?) {
        if (target == null) {
            logger.info("[AgentClient] No process handle to terminate: cmd=$traceLabel")
            return
        }
        logger.info(
            "[AgentClient] process termination start: cmd=$traceLabel, pid=${target.pid()}, alive=${target.isAlive}"
        )
        if (!target.isAlive) {
            logger.info("[AgentClient] process already exited: cmd=$traceLabel, exit=${safeExitValue(target)}")
            return
        }

        target.destroy()
        val exitedGracefully = target.waitFor(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        logger.info(
            "[AgentClient] process destroy result: cmd=$traceLabel, pid=${target.pid()}, " +
                "exited=$exitedGracefully, alive=${target.isAlive}"
        )
        if (exitedGracefully) {
            logger.info("[AgentClient] process exited gracefully: cmd=$traceLabel, exit=${safeExitValue(target)}")
            return
        }

        logger.warn("[AgentClient] process did not exit after destroy, forcing: cmd=$traceLabel, pid=${target.pid()}")
        target.destroyForcibly()
        val exitedForced = target.waitFor(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        logger.info(
            "[AgentClient] process force-destroy result: cmd=$traceLabel, pid=${target.pid()}, " +
                "exited=$exitedForced, alive=${target.isAlive}, exit=${safeExitValue(target)}"
        )
    }

    private fun safeExitValue(target: Process): Int? {
        return runCatching { target.exitValue() }.getOrNull()
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
