package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import kotlinx.serialization.json.JsonElement
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.forEach
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * @author: pony
 * @date: Created in 14:26 2026/4/14
 */
class TerminalClientSessionOperations : ClientSessionOperations, FileSystemOperations, TerminalOperations {
    private val activeTerminals = ConcurrentHashMap<String, Process>()

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        println("Agent requested permissions for tool call: ${toolCall.title}. Choose one of the following options:")
        for ((i, permission) in permissions.withIndex()) {
            println("${i + 1}. ${permission.name}")
        }
        while (true) {
            val read = readln()
            val optionIndex = read.toIntOrNull()
            if (optionIndex != null && optionIndex in permissions.indices) {
                return RequestPermissionResponse(RequestPermissionOutcome.Selected(permissions[optionIndex].optionId), _meta)
            }
            println("Invalid option selected. Try again.")
        }
    }

    override suspend fun notify(
        notification: SessionUpdate,
        _meta: JsonElement?,
    ) {
        println("Agent sent notification:")
        notification.render()
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

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        Paths.get(path).writeText(content)
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
        if (cwd != null) {
            processBuilder.directory(java.io.File(cwd))
        }
        env.forEach { processBuilder.environment()[it.name] = it.value }

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
        val output = if (stderr.isNotEmpty()) "$stdout\nSTDERR:\n$stderr" else stdout

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
}
