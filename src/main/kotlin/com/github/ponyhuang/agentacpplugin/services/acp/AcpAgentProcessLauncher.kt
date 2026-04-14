package com.github.ponyhuang.agentacpplugin.services.acp

import java.io.Closeable
import java.io.File
import java.nio.file.Path

data class LaunchedAgentProcess(
    val commandLine: String,
    val commandParts: List<String>,
    val process: Process,
) : Closeable {
    override fun close() {
        process.destroy()
    }
}

class AcpAgentProcessLauncher {
    fun launch(commandLine: String, workingDirectory: Path): LaunchedAgentProcess {
        val parts = tokenize(commandLine)
        require(parts.isNotEmpty()) { "Agent command cannot be empty" }
        val resolvedParts = parts.toMutableList()
        resolvedParts[0] = resolveExecutable(parts.first())
        val process = ProcessBuilder(resolvedParts)
            .directory(workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        return LaunchedAgentProcess(commandLine = commandLine, commandParts = resolvedParts, process = process)
    }

    internal fun tokenize(commandLine: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        for (ch in commandLine.trim()) {
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }

                ch == '\\' -> escaping = true
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }

    internal fun resolveExecutable(command: String): String {
        if (!isWindows() || command.contains('\\') || command.contains('/') || command.substringAfterLast('.', "").isNotEmpty()) {
            return command
        }
        val candidates = runCatching {
            ProcessBuilder("where.exe", command)
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { reader -> reader.readLines() }
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val preferred = listOf(".cmd", ".bat", ".exe", ".com")
        return preferred.firstNotNullOfOrNull { suffix ->
            candidates.firstOrNull { it.endsWith(suffix, ignoreCase = true) }
        } ?: candidates.firstOrNull() ?: command
    }

    private fun isWindows(): Boolean = File.separatorChar == '\\'
}
