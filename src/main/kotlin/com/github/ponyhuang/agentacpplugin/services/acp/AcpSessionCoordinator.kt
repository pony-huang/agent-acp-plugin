package com.github.ponyhuang.agentacpplugin.services.acp

import com.github.ponyhuang.agentacpplugin.services.session.RegisteredSession
import java.nio.file.Path

class AcpSessionCoordinator(
    private val clientFacade: AcpClientFacade,
) {
    suspend fun connect(
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
        ingress: SessionUpdateIngress,
        permissionRequestHandler: PermissionRequestHandler,
    ): RegisteredSession {
        return clientFacade.connect(endpointId, endpointName, commandLine, workspaceRoot, ingress, permissionRequestHandler)
    }

    suspend fun submitPrompt(sessionId: String, prompt: String, ingress: SessionUpdateIngress) {
        clientFacade.prompt(sessionId, prompt, ingress)
    }

    suspend fun cancel(sessionId: String) {
        clientFacade.cancel(sessionId)
    }

    fun disconnect(sessionId: String) {
        clientFacade.disconnect(sessionId)
    }
}
