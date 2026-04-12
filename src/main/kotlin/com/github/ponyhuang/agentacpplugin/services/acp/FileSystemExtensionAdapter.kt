package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileSystemExtensionAdapter(
    private val workspaceRoot: Path,
) : FileSystemOperations {
    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        val resolved = resolve(path)
        val lines = resolved.readText().lines()
        val from = line?.toInt()?.coerceAtLeast(1)?.minus(1) ?: 0
        val toExclusive = if (limit == null) lines.size else (from + limit.toInt()).coerceAtMost(lines.size)
        return ReadTextFileResponse(lines.subList(from, toExclusive).joinToString(separator = "\n"))
    }

    override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?): WriteTextFileResponse {
        resolve(path).writeText(content)
        return WriteTextFileResponse()
    }

    private fun resolve(path: String): Path {
        val target = workspaceRoot.resolve(path).normalize().absolute()
        require(target.startsWith(workspaceRoot.absolute())) { "File access must stay inside the project workspace" }
        return target
    }
}
