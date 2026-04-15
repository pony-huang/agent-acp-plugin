package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AcpPermissionRequestService : Disposable {

    data class PendingPermissionRequest(
        val requestId: String,
        val toolCall: SessionUpdate.ToolCallUpdate,
        val permissions: List<PermissionOption>,
        val meta: JsonElement?,
        internal val response: CompletableDeferred<RequestPermissionResponse>,
    )

    private val _requests = MutableSharedFlow<PendingPermissionRequest>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    private val pendingRequests = ConcurrentHashMap<String, PendingPermissionRequest>()

    val requests: SharedFlow<PendingPermissionRequest> = _requests.asSharedFlow()

    fun hasActiveSubscribers(): Boolean = _requests.subscriptionCount.value > 0

    suspend fun requestPermissions(
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        meta: JsonElement?,
    ): RequestPermissionResponse {
        val request = PendingPermissionRequest(
            requestId = UUID.randomUUID().toString(),
            toolCall = toolCall,
            permissions = permissions,
            meta = meta,
            response = CompletableDeferred(),
        )
        pendingRequests[request.requestId] = request
        try {
            _requests.emit(request)
            return request.response.await()
        } finally {
            pendingRequests.remove(request.requestId)
        }
    }

    fun submitSelection(requestId: String, optionId: PermissionOptionId): Boolean {
        val request = pendingRequests[requestId] ?: return false
        return request.response.complete(
            RequestPermissionResponse(
                outcome = RequestPermissionOutcome.Selected(optionId),
                _meta = request.meta,
            )
        )
    }

    override fun dispose() {
        val outstanding = pendingRequests.values.toList()
        pendingRequests.clear()
        outstanding.forEach { request ->
            request.response.cancel()
        }
    }
}
