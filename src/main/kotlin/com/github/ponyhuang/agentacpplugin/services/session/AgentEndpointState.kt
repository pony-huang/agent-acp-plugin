package com.github.ponyhuang.agentacpplugin.services.session

enum class AgentConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class AgentEndpointState(
    val endpointId: String,
    val displayName: String,
    val connectionStatus: AgentConnectionStatus = AgentConnectionStatus.DISCONNECTED,
    val capabilitiesSummary: String = "",
    val lastErrorSummary: String? = null,
    val activeSessionIds: Set<String> = emptySet(),
)
