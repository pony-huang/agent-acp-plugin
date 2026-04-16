package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionUpdate
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var _currentSession: ClientSession? = null

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
    @OptIn(UnstableApi::class)
    suspend fun createSession(agentDefinition: AgentRegistry.AgentDefinition, cwd: String) {
        _isLoading.value = true
        try {
            val scope = CoroutineScope(Dispatchers.Default)
            coroutineScope = scope

            val configService = project.service<AcpAgentsConfigService>()
            val sessionUpdateHandler: suspend (SessionUpdate) -> Unit = { update ->
                handleSessionUpdate(update)
            }
            client = configService.createClientBridge(agentDefinition.displayName, scope, sessionUpdateHandler)

            if (client != null) {
                val info = client!!.connect()
                if (info != null) {
                    _currentAgent.value = info

                    // Create actual ACP session
                    val session = client!!.newSession()
                    if (session != null) {
                        _currentSession = session
                        _availableModes.value = session.availableModes
                        _availableModels.value = session.availableModels
                        _currentModeId.value = session.currentMode.value.toString()
                        _currentModelId.value = session.currentModel.value.toString()
                        _isConnected.value = true
                    }
                }
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Send a prompt to the current session.
     */
    @OptIn(UnstableApi::class)
    suspend fun sendPrompt(text: String) {
        val session = _currentSession ?: return

        // Add user message immediately
        addMessage("user", text)

        // Collect streaming updates via Flow
        val content = listOf(ContentBlock.Text(text))
        session.prompt(content).collect { event ->
            when (event) {
                is Event.SessionUpdateEvent -> handleSessionUpdate(event.update)
                is Event.PromptResponseEvent -> handlePromptResponse(event.response)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun handleSessionUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                // AgentMessageChunk content is streamed - handle appropriately
                // Note: content extraction depends on SDK version specifics
            }
            is SessionUpdate.ToolCall -> addToolCallMessage(update)
            is SessionUpdate.ToolCallUpdate -> updateToolCallMessage(update)
            is SessionUpdate.UsageUpdate -> { /* ignore usage updates for now */ }
            else -> { /* ignore other update types */ }
        }
    }

    private fun appendToLastAssistantMessage(content: String) {
        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = messages.dropLast(1) + updatedMsg
        }
    }

    private fun addToolCallMessage(toolCall: SessionUpdate.ToolCall) {
        val message = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "assistant",
            content = "[Tool Call: ${toolCall.title}]"
        )
        _messages.value = _messages.value + message
    }

    private fun updateToolCallMessage(update: SessionUpdate.ToolCallUpdate) {
        // Update the tool call in progress if needed
    }

    private fun handlePromptResponse(response: PromptResponse) {
        // Final response after streaming completes - response only has stopReason and userMessageId
        // The actual content would have come through AgentMessageChunk updates
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
     * Resume an existing ACP session with the specified session ID.
     */
    @OptIn(UnstableApi::class)
    suspend fun resumeSession(sessionId: String, agentDefinition: AgentRegistry.AgentDefinition, cwd: String) {
        _isLoading.value = true
        try {
            val scope = CoroutineScope(Dispatchers.Default)
            coroutineScope = scope

            val configService = project.service<AcpAgentsConfigService>()
            val sessionUpdateHandler: suspend (SessionUpdate) -> Unit = { update ->
                handleSessionUpdate(update)
            }
            client = configService.createClientBridge(agentDefinition.displayName, scope, sessionUpdateHandler)

            if (client != null) {
                val info = client!!.connect()
                if (info != null) {
                    _currentAgent.value = info

                    // Resume existing ACP session with session ID
                    val session = client!!.loadSession(SessionId(sessionId))
                    if (session != null) {
                        _currentSession = session
                        _availableModes.value = session.availableModes
                        _availableModels.value = session.availableModels
                        _currentModeId.value = session.currentMode.value.toString()
                        _currentModelId.value = session.currentModel.value.toString()
                        _isConnected.value = true
                    }
                }
            }
        } finally {
            _isLoading.value = false
        }
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
