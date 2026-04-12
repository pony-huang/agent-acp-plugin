package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList

data class PendingPermissionRequest(
    val toolTitle: String,
    val options: List<String>,
)

class PermissionRequestHandler {
    private val listeners = CopyOnWriteArrayList<(PendingPermissionRequest?) -> Unit>()
    @Volatile
    private var latest: PendingPermissionRequest? = null

    suspend fun request(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        _meta: JsonElement?,
    ): RequestPermissionResponse {
        latest = PendingPermissionRequest(
            toolTitle = toolCall.title ?: "Tool",
            options = permissions.map { it.name },
        )
        notifyListeners()
        val selected = permissions.firstOrNull { it.kind == PermissionOptionKind.ALLOW_ONCE }
            ?: permissions.firstOrNull { it.kind == PermissionOptionKind.REJECT_ONCE }
            ?: permissions.first()
        latest = null
        notifyListeners()
        return RequestPermissionResponse(RequestPermissionOutcome.Selected(selected.optionId), _meta)
    }

    fun latest(): PendingPermissionRequest? = latest

    fun addListener(listener: (PendingPermissionRequest?) -> Unit): () -> Unit {
        listeners += listener
        listener(latest)
        return { listeners -= listener }
    }

    private fun notifyListeners() {
        val snapshot = latest
        listeners.forEach { it(snapshot) }
    }
}
