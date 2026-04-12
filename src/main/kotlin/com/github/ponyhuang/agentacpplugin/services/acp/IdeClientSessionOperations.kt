package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.common.TerminalOperations
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement

class IdeClientSessionOperations(
    private val fileSystemAdapter: FileSystemExtensionAdapter,
    private val terminalAdapter: TerminalExtensionAdapter,
    private val permissionRequestHandler: PermissionRequestHandler,
    private val sessionNotificationHandler: (SessionUpdate) -> Unit,
) : ClientSessionOperations,
    FileSystemOperations by fileSystemAdapter,
    TerminalOperations by terminalAdapter {

    override suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        return permissionRequestHandler.request(toolCall, permissions, _meta)
    }

    override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) {
        sessionNotificationHandler(notification)
    }
}
