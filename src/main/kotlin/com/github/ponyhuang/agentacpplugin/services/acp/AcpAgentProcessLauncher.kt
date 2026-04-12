package com.github.ponyhuang.agentacpplugin.services.acp

import java.io.Closeable
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
        val process = ProcessBuilder(parts)
            .directory(workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        return LaunchedAgentProcess(commandLine = commandLine, commandParts = parts, process = process)
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
}
