package com.github.ponyhuang.agentacpplugin.toolwindow

import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicLong
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
    private val connectAgent: suspend (SwitchRequest) -> Unit,
    private val onSwitchFailed: (AgentRegistry.InstalledAgent, Throwable) -> Unit = { _, _ -> }
) {
    data class SwitchRequest(
        val traceId: String,
        val agent: AgentRegistry.InstalledAgent,
    )

    companion object {
        private val logger = Logger.getInstance(AgentSwitchController::class.java)
        private val traceCounter = AtomicLong(0)
    }

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private var pendingRequest: SwitchRequest? = null
    private var switchJob: Job? = null

    fun requestSwitch(agent: AgentRegistry.InstalledAgent): String? {
        val currentlyConnected = isConnected()
        val currentAgentId = currentConnectedAgentId()
        if (!_isSwitching.value && isConnected() && currentConnectedAgentId() == agent.id) {
            logger.info(
                "[AgentSwitch] Ignored switch request for already-connected agent id=${agent.id}, " +
                    "connected=$currentlyConnected, currentAgentId=$currentAgentId"
            )
            return null
        }

        val request = SwitchRequest(
            traceId = "switch-${traceCounter.incrementAndGet()}",
            agent = agent
        )
        val previousPending = pendingRequest
        pendingRequest = request
        logger.info(
            "[AgentSwitch][${request.traceId}] Enqueued request target=${agent.id}, " +
                "connected=$currentlyConnected, currentAgentId=$currentAgentId, " +
                "switching=${_isSwitching.value}, replacedPending=${previousPending?.agent?.id ?: "<none>"}"
        )
        if (switchJob?.isActive == true) {
            logger.info("[AgentSwitch][${request.traceId}] Existing switch job is active, request will wait in queue")
            return request.traceId
        }

        switchJob = scope.launch {
            logger.info("[AgentSwitch] Starting drain job")
            drainPendingSwitches()
        }
        return request.traceId
    }

    private suspend fun drainPendingSwitches() {
        while (true) {
            val request = pendingRequest ?: break
            pendingRequest = null
            val agent = request.agent

            logger.info(
                "[AgentSwitch][${request.traceId}] Dequeued request target=${agent.id}, " +
                    "connected=${isConnected()}, currentAgentId=${currentConnectedAgentId()}"
            )
            if (isConnected() && currentConnectedAgentId() == agent.id) {
                logger.info("[AgentSwitch][${request.traceId}] Target already connected when dequeued, skipping")
                continue
            }

            _isSwitching.value = true
            logger.info("[AgentSwitch][${request.traceId}] Switching flag -> true")
            try {
                logger.info("[AgentSwitch][${request.traceId}] Connecting target agent id=${agent.id}")
                connectAgent(request)
                logger.info(
                    "[AgentSwitch][${request.traceId}] Connect finished, connected=${isConnected()}, " +
                        "currentAgentId=${currentConnectedAgentId()}"
                )
            } catch (t: Throwable) {
                logger.warn("[AgentSwitch][${request.traceId}] Switch failed for target=${agent.id}", t)
                onSwitchFailed(agent, t)
            } finally {
                if (pendingRequest == null) {
                    _isSwitching.value = false
                    logger.info("[AgentSwitch][${request.traceId}] Switching flag -> false (queue empty)")
                } else {
                    logger.info(
                        "[AgentSwitch][${request.traceId}] Keeping switching flag true because pending=" +
                            pendingRequest?.agent?.id
                    )
                }
            }
        }

        _isSwitching.value = false
        logger.info("[AgentSwitch] Drain job finished, switching flag -> false")
    }
}
