package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Traffic entry types matching acp-ui's traffic store.
 */
enum class TrafficDirection { IN, OUT }
enum class TrafficType { REQUEST, RESPONSE, NOTIFICATION }

/**
 * A single traffic log entry.
 */
data class TrafficEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val direction: TrafficDirection,
    val type: TrafficType,
    val method: String,
    val requestId: String? = null,
    val payload: String,
    val error: Boolean = false,
)

/**
 * Filter options for traffic view.
 */
enum class TrafficFilter {
    ALL, REQUESTS, RESPONSES, NOTIFICATIONS
}

/**
 * Traffic monitoring service for ACP JSON-RPC message tracking.
 * Inspired by acp-ui's traffic.ts store.
 */
@Service(Service.Level.PROJECT)
class AcpTrafficService : Disposable {

    // Traffic entries storage
    private val _entries = MutableStateFlow<List<TrafficEntry>>(emptyList())
    val entries: StateFlow<List<TrafficEntry>> = _entries.asStateFlow()

    // Filter state
    private val _filter = MutableStateFlow(TrafficFilter.ALL)
    val filter: StateFlow<TrafficFilter> = _filter.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Pause state
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Max entries to prevent memory issues
    private val maxEntries = 500

    /**
     * Get filtered entries based on current filter and search.
     */
    val filteredEntries: StateFlow<List<TrafficEntry>>

    init {
        // Initialize filteredEntries using combine
        val flow = MutableStateFlow<List<TrafficEntry>>(emptyList())
        filteredEntries = flow.asStateFlow()

        // Launch a coroutine to update filtered entries
        CoroutineScope(Dispatchers.Main).launch {
            combine(_entries, _filter, _searchQuery) { entries: List<TrafficEntry>, filter: TrafficFilter, query: String ->
                var result = entries

                // Apply type filter
                result = when (filter) {
                    TrafficFilter.ALL -> result
                    TrafficFilter.REQUESTS -> result.filter { it.type == TrafficType.REQUEST }
                    TrafficFilter.RESPONSES -> result.filter { it.type == TrafficType.RESPONSE }
                    TrafficFilter.NOTIFICATIONS -> result.filter { it.type == TrafficType.NOTIFICATION }
                }

                // Apply search filter
                if (query.isNotBlank()) {
                    result = result.filter { entry: TrafficEntry ->
                        entry.method.contains(query, ignoreCase = true) ||
                                entry.payload.contains(query, ignoreCase = true)
                    }
                }

                result
            }.collect { filtered: List<TrafficEntry> ->
                (flow as MutableStateFlow).value = filtered
            }
        }
    }

    /**
     * Add a new traffic entry.
     */
    fun addEntry(
        direction: TrafficDirection,
        type: TrafficType,
        method: String,
        requestId: String? = null,
        payload: String,
        error: Boolean = false,
    ) {
        if (_isPaused.value) return

        val entry = TrafficEntry(
            direction = direction,
            type = type,
            method = method,
            requestId = requestId,
            payload = payload,
            error = error,
        )

        _entries.value = (_entries.value + entry).takeLast(maxEntries)
    }

    /**
     * Add an incoming JSON-RPC request from agent.
     */
    fun addIncomingRequest(method: String, requestId: String, payload: String) {
        addEntry(TrafficDirection.IN, TrafficType.REQUEST, method, requestId, payload)
    }

    /**
     * Add an incoming JSON-RPC response from agent.
     */
    fun addIncomingResponse(method: String, requestId: String, payload: String, error: Boolean = false) {
        addEntry(TrafficDirection.IN, TrafficType.RESPONSE, method, requestId, payload, error)
    }

    /**
     * Add an incoming notification from agent.
     */
    fun addIncomingNotification(method: String, payload: String) {
        addEntry(TrafficDirection.IN, TrafficType.NOTIFICATION, method, null, payload)
    }

    /**
     * Add an outgoing JSON-RPC request to agent.
     */
    fun addOutgoingRequest(method: String, requestId: String, payload: String) {
        addEntry(TrafficDirection.OUT, TrafficType.REQUEST, method, requestId, payload)
    }

    /**
     * Add an outgoing JSON-RPC response to agent.
     */
    fun addOutgoingResponse(method: String, requestId: String, payload: String, error: Boolean = false) {
        addEntry(TrafficDirection.OUT, TrafficType.RESPONSE, method, requestId, payload, error)
    }

    /**
     * Add an outgoing notification to agent.
     */
    fun addOutgoingNotification(method: String, payload: String) {
        addEntry(TrafficDirection.OUT, TrafficType.NOTIFICATION, method, null, payload)
    }

    /**
     * Clear all traffic entries.
     */
    fun clear() {
        _entries.value = emptyList()
    }

    /**
     * Toggle pause state.
     */
    fun togglePause() {
        _isPaused.value = !_isPaused.value
    }

    /**
     * Set filter.
     */
    fun setFilter(newFilter: TrafficFilter) {
        _filter.value = newFilter
    }

    /**
     * Set search query.
     */
    fun setSearch(query: String) {
        _searchQuery.value = query
    }

    /**
     * Clear search query.
     */
    fun clearSearch() {
        _searchQuery.value = ""
    }

    override fun dispose() {
        clear()
    }
}