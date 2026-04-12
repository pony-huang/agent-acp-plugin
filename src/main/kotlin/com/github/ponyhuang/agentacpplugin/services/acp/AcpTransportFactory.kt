package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

class AcpTransportFactory {
    fun create(scope: CoroutineScope, launchedProcess: LaunchedAgentProcess): Transport {
        return StdioTransport(
            parentScope = scope,
            ioDispatcher = Dispatchers.IO,
            input = launchedProcess.process.inputStream.asSource().buffered(),
            output = launchedProcess.process.outputStream.asSink().buffered(),
        )
    }
}
