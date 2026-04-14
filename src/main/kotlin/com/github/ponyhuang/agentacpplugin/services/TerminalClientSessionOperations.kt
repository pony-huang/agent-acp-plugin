package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.text.Charsets.UTF_8

class TerminalClientSessionOperations(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
    private val sessionUpdateSink: suspend (SessionUpdate) -> Unit,
) : ClientSessionOperations, FileSystemOperations, TerminalOperations {
    private val activeTerminals = ConcurrentHashMap<String, ActiveTerminal>()

    private data class ActiveTerminal(
        val process: Process,
        val outputByteLimit: ULong?,
        val stdout: StringBuffer,
        val stderr: StringBuffer,
        val stdoutJob: Job,
        val stderrJob: Job,
    )

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        val permissionService = project.service<AcpPermissionRequestService>()
        if (permissionService.hasActiveSubscribers()) {
            return permissionService.requestPermissions(toolCall, permissions, _meta)
        }
        return autoApprovePermissions(permissions, _meta)
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        sessionUpdateSink(notification)
    }

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        val resolvedPath = resolveProjectPath(path)
        val content = if (line != null || limit != null) {
            val lines = resolvedPath.readLines()
            val startIndex = (line?.toInt()?.minus(1) ?: 0).coerceAtLeast(0)
            val endIndex = if (limit == null) {
                lines.size
            } else {
                (startIndex + limit.toInt()).coerceAtMost(lines.size)
            }
            lines.subList(startIndex.coerceAtMost(lines.size), endIndex).joinToString(System.lineSeparator())
        } else {
            resolvedPath.readText()
        }
        return ReadTextFileResponse(content)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        val resolvedPath = resolveProjectPath(path)
        resolvedPath.parent?.createDirectories()
        resolvedPath.writeText(content)
        return WriteTextFileResponse()
    }

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        val processBuilder = ProcessBuilder(listOf(command) + args)
        processBuilder.directory(java.io.File(cwd ?: projectSessionRoot(project)))
        env.forEach { processBuilder.environment()[it.name] = it.value }

        val process = processBuilder.start()
        val terminalId = UUID.randomUUID().toString()
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val stdoutJob = captureStream(process.inputStream, stdout)
        val stderrJob = captureStream(process.errorStream, stderr)

        activeTerminals[terminalId] = ActiveTerminal(
            process = process,
            outputByteLimit = outputByteLimit,
            stdout = stdout,
            stderr = stderr,
            stdoutJob = stdoutJob,
            stderrJob = stderrJob,
        )

        return CreateTerminalResponse(terminalId)
    }

    override suspend fun terminalOutput(
        terminalId: String,
        _meta: JsonElement?,
    ): TerminalOutputResponse {
        val terminal = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
        val combined = buildString {
            append(terminal.stdout.toString())
            if (terminal.stderr.isNotEmpty()) {
                if (isNotEmpty()) {
                    appendLine()
                }
                append("STDERR:")
                appendLine()
                append(terminal.stderr.toString())
            }
        }
        val limited = applyOutputLimit(combined, terminal.outputByteLimit)

        return TerminalOutputResponse(
            output = limited.output,
            truncated = limited.truncated,
            exitStatus = terminal.process.exitStatusOrNull(),
        )
    }

    override suspend fun terminalRelease(
        terminalId: String,
        _meta: JsonElement?,
    ): ReleaseTerminalResponse {
        activeTerminals.remove(terminalId)?.close()
        return ReleaseTerminalResponse()
    }

    override suspend fun terminalWaitForExit(
        terminalId: String,
        _meta: JsonElement?,
    ): WaitForTerminalExitResponse {
        val terminal = activeTerminals[terminalId] ?: error("Terminal not found: $terminalId")
        val exitCode = withContext(Dispatchers.IO) {
            terminal.process.waitFor()
        }
        return WaitForTerminalExitResponse(exitCode.toUInt())
    }

    override suspend fun terminalKill(
        terminalId: String,
        _meta: JsonElement?,
    ): KillTerminalCommandResponse {
        activeTerminals[terminalId]?.kill()
        return KillTerminalCommandResponse()
    }

    suspend fun close() {
        val terminals = activeTerminals.values.toList()
        activeTerminals.clear()
        terminals.forEach { terminal ->
            terminal.close()
        }
    }

    private fun resolveProjectPath(path: String): Path {
        val candidate = Paths.get(path)
        return if (candidate.isAbsolute) {
            candidate.normalize()
        } else {
            Paths.get(projectSessionRoot(project)).resolve(candidate).normalize()
        }
    }

    private fun captureStream(stream: java.io.InputStream, buffer: StringBuffer): Job {
        return coroutineScope.launch(Dispatchers.IO) {
            stream.bufferedReader().use { reader ->
                val chars = CharArray(1024)
                while (true) {
                    val read = reader.read(chars)
                    if (read < 0) {
                        break
                    }
                    if (read > 0) {
                        buffer.append(chars, 0, read)
                    }
                }
            }
        }
    }

    private suspend fun ActiveTerminal.kill() {
        if (process.isAlive) {
            process.destroy()
        }
    }

    private suspend fun ActiveTerminal.close() {
        if (process.isAlive) {
            process.destroy()
        }
        stdoutJob.cancelAndJoin()
        stderrJob.cancelAndJoin()
    }

    private data class LimitedOutput(
        val output: String,
        val truncated: Boolean,
    )

    private fun autoApprovePermissions(
        permissions: List<PermissionOption>,
        meta: JsonElement?,
    ): RequestPermissionResponse {
        val selectedOptionId = permissions.firstOrNull {
            it.kind == PermissionOptionKind.ALLOW_ALWAYS || it.kind == PermissionOptionKind.ALLOW_ONCE
        }?.optionId ?: permissions.firstOrNull()?.optionId
            ?: error("ACP agent requested permissions with no options")

        return RequestPermissionResponse(
            RequestPermissionOutcome.Selected(PermissionOptionId(selectedOptionId.value)),
            meta,
        )
    }

    private fun applyOutputLimit(output: String, outputByteLimit: ULong?): LimitedOutput {
        if (outputByteLimit == null) {
            return LimitedOutput(output, truncated = false)
        }

        val maxBytes = outputByteLimit.toLong().coerceAtLeast(0)
        val bytes = output.toByteArray(UTF_8)
        if (bytes.size <= maxBytes) {
            return LimitedOutput(output, truncated = false)
        }

        val truncatedBytes = bytes.copyOfRange(bytes.size - maxBytes.toInt(), bytes.size)
        return LimitedOutput(String(truncatedBytes, UTF_8), truncated = true)
    }

    private fun Process.exitStatusOrNull(): TerminalExitStatus? {
        return if (isAlive) {
            null
        } else {
            TerminalExitStatus(exitCode = exitValue().toUInt())
        }
    }
}
