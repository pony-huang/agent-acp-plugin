package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.ModelInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified service for managing ACP session state.
 * Inspired by Pinia store pattern from acp-ui, adapted for Kotlin coroutines.
 */
@Service(Service.Level.PROJECT)
class AcpSessionService(private val project: Project) : Disposable {

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Chat messages
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Available modes
    private val _availableModes = MutableStateFlow<List<SessionMode>>(emptyList())
    val availableModes: StateFlow<List<SessionMode>> = _availableModes.asStateFlow()

    private val _currentModeId = MutableStateFlow("")
    val currentModeId: StateFlow<String> = _currentModeId.asStateFlow()

    // Available models
    @OptIn(UnstableApi::class)
    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    @OptIn(UnstableApi::class)
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _currentModelId = MutableStateFlow("")
    val currentModelId: StateFlow<String> = _currentModelId.asStateFlow()

    // Current agent
    private val _currentAgent = MutableStateFlow<AgentInfo?>(null)
    val currentAgent: StateFlow<AgentInfo?> = _currentAgent.asStateFlow()

    // ACP Client
    private var client: AcpAgentClient? = null
    private var coroutineScope: CoroutineScope? = null

    /**
     * Chat message model
     */
    data class ChatMessage(
        val id: String,
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Create a new ACP session with the specified agent.
     */
    suspend fun createSession(agentDefinition: AgentRegistry.AgentDefinition, cwd: String) {
        _isLoading.value = true
        try {
            val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            coroutineScope = scope

            val configService = project.service<AcpAgentsConfigService>()
            client = configService.createClientBridge(agentDefinition.displayName, scope)

            if (client != null) {
                val info = client!!.connect()
                if (info != null) {
                    _currentAgent.value = info
                    _isConnected.value = true
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Send a prompt to the current session.
     */
    suspend fun sendPrompt(text: String) {
        // Implementation deferred - requires ACP protocol full implementation
    }

    /**
     * Set the current session mode.
     */
    suspend fun setMode(modeId: String) {
        _currentModeId.value = modeId
    }

    /**
     * Set the current model.
     */
    suspend fun setModel(modelId: String) {
        _currentModelId.value = modelId
    }

    /**
     * Disconnect from the current session.
     */
    fun disconnect() {
        client = null
        _isConnected.value = false
        _currentAgent.value = null
    }

    /**
     * Add a message to the chat history.
     */
    fun addMessage(role: String, content: String) {
        val newMessage = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = role,
            content = content
        )
        _messages.value = _messages.value + newMessage
    }

    override fun dispose() {
        disconnect()
    }
}
