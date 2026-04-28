package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Serializes agent switch requests so UI selection changes are handled atomically.
 */
class AgentSwitchController(
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val currentConnectedAgentId: () -> String?,
    private val disconnectCurrentAgent: suspend () -> Unit,
    private val connectAgent: suspend (AgentRegistry.InstalledAgent) -> Unit,
    private val onSwitchFailed: (AgentRegistry.InstalledAgent, Throwable) -> Unit = { _, _ -> }
) {
    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private var pendingAgent: AgentRegistry.InstalledAgent? = null
    private var switchJob: Job? = null

    fun requestSwitch(agent: AgentRegistry.InstalledAgent) {
        if (!_isSwitching.value && isConnected() && currentConnectedAgentId() == agent.id) {
            return
        }

        pendingAgent = agent
        if (switchJob?.isActive == true) {
            return
        }

        switchJob = scope.launch {
            drainPendingSwitches()
        }
    }

    private suspend fun drainPendingSwitches() {
        while (true) {
            val agent = pendingAgent ?: break
            pendingAgent = null

            if (isConnected() && currentConnectedAgentId() == agent.id) {
                continue
            }

            _isSwitching.value = true
            try {
                if (isConnected()) {
                    disconnectCurrentAgent()
                }
                connectAgent(agent)
            } catch (t: Throwable) {
                onSwitchFailed(agent, t)
            } finally {
                if (pendingAgent == null) {
                    _isSwitching.value = false
                }
            }
        }

        _isSwitching.value = false
    }
}
