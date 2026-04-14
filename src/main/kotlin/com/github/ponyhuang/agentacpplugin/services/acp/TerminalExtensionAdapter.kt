package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TerminalExtensionAdapter(
    private val workspaceRoot: Path,
) : TerminalOperations {
    private val terminals = ConcurrentHashMap<String, Process>()

    override suspend fun terminalCreate(
        command: String,
        args: List<String>,
        cwd: String?,
        env: List<EnvVariable>,
        outputByteLimit: ULong?,
        _meta: JsonElement?,
    ): CreateTerminalResponse {
        val builder = ProcessBuilder(listOf(command) + args)
        builder.directory((cwd?.let(workspaceRoot::resolve) ?: workspaceRoot).toFile())
        env.forEach { builder.environment()[it.name] = it.value }
        val process = withContext(Dispatchers.IO) {
            builder.start()
        }
        val terminalId = UUID.randomUUID().toString()
        terminals[terminalId] = process
        return CreateTerminalResponse(terminalId)
    }

    override suspend fun terminalOutput(terminalId: String, _meta: JsonElement?): TerminalOutputResponse {
        val process = terminals.getValue(terminalId)
        val output = process.inputStream.bufferedReader().readText()
        return TerminalOutputResponse(output = output, truncated = false)
    }

    override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?): ReleaseTerminalResponse {
        terminals.remove(terminalId)
        return ReleaseTerminalResponse()
    }

    override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?): WaitForTerminalExitResponse {
        val code = withContext(Dispatchers.IO) {
            terminals.getValue(terminalId).waitFor()
        }
        return WaitForTerminalExitResponse(exitCode = code.toUInt())
    }

    override suspend fun terminalKill(terminalId: String, _meta: JsonElement?): KillTerminalCommandResponse {
        terminals.remove(terminalId)?.destroy()
        return KillTerminalCommandResponse()
    }
}
