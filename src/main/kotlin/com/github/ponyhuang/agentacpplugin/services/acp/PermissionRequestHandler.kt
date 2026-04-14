package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

data class PendingPermissionRequest(
    val toolTitle: String,
    val options: List<String>,
)

class PermissionRequestHandler {
    private val logger = Logger.getInstance(PermissionRequestHandler::class.java)
    private val _pendingRequest = MutableStateFlow<PendingPermissionRequest?>(null)
    val pendingRequest: StateFlow<PendingPermissionRequest?> = _pendingRequest.asStateFlow()

    fun request(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        _pendingRequest.value = PendingPermissionRequest(
            toolTitle = toolCall.title ?: "Tool",
            options = permissions.map { it.name },
        )
        val selected = permissions.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ONCE }
            ?: permissions.firstOrNull { it.kind == PermissionOptionKind.REJECT_ONCE }
            ?: permissions.first()
        AcpProtocolDebugLogger.logPermissionSelection(logger, toolCall.title ?: "Tool", selected.name, selected.kind.name)
        _pendingRequest.value = null
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(selected.optionId), _meta)
    }
}
