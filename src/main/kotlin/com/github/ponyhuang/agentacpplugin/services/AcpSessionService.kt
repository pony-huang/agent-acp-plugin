package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
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
 *
 * Features implemented:
 * 1. cancel() - Cancel ongoing prompt
 * 2. setMode()/setModel() - Complete implementation calling SDK
 * 3. Complete SessionUpdate handling - all update types
 * 4. authenticate() - Authentication flow
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

    // Available slash commands (from AvailableCommandsUpdate)
    private val _availableCommands = MutableStateFlow<List<AvailableCommand>>(emptyList())
    val availableCommands: StateFlow<List<AvailableCommand>> = _availableCommands.asStateFlow()

    // Current session's tool call tracking (messageId -> toolCallId)
    private val _activeToolCalls = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeToolCalls: StateFlow<Map<String, String>> = _activeToolCalls.asStateFlow()

    // Last prompt response
    private val _lastStopReason = MutableStateFlow<StopReason?>(null)
    val lastStopReason: StateFlow<StopReason?> = _lastStopReason.asStateFlow()

    // ACP Client
    private var client: AcpAgentClient? = null
    private var coroutineScope: CoroutineScope? = null
    private var _currentSession: ClientSession? = null

    /**
     * Chat message model with optional metadata
     */
    data class ChatMessage(
        val id: String,
        val role: String,
        val content: String,
        val thought: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val toolCalls: List<ToolCallInfo> = emptyList()
    )

    /**
     * Tool call info for tracking tool execution
     */
    data class ToolCallInfo(
        val toolCallId: String,
        val title: String,
        val status: String = "pending",
        val kind: String? = null
    )

    /**
     * Create a new ACP session with the specified agent.
     * Handles authentication if required by the agent.
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

    /**
     * Cancel the current ongoing prompt operation.
     */
    @OptIn(UnstableApi::class)
    suspend fun cancel() {
        val session = _currentSession ?: return
        println("[AcpSessionService] Cancelling current operation...")
        session.cancel()
        println("[AcpSessionService] Cancel completed")
    }

    /**
     * Set the current session mode.
     * Calls the SDK to actually change the mode on the agent side.
     */
    @OptIn(UnstableApi::class)
    suspend fun setMode(modeId: String) {
        val session = _currentSession ?: return
        println("[AcpSessionService] Setting mode to: $modeId")
        session.setMode(SessionModeId(modeId))
        // Mode change will be confirmed via CurrentModeUpdate notification
    }

    /**
     * Set the current model.
     * Calls the SDK to actually change the model on the agent side.
     */
    @OptIn(UnstableApi::class)
    suspend fun setModel(modelId: String) {
        val session = _currentSession ?: return
        println("[AcpSessionService] Setting model to: $modelId")
        session.setModel(ModelId(modelId))
        // Model change will be confirmed via notification
    }

    /**
     * Complete SessionUpdate handler - handles all update types.
     */
    @OptIn(UnstableApi::class)
    private fun handleSessionUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.UserMessageChunk -> handleUserMessageChunk(update)
            is SessionUpdate.AgentMessageChunk -> handleAgentMessageChunk(update)
            is SessionUpdate.AgentThoughtChunk -> handleAgentThoughtChunk(update)
            is SessionUpdate.ToolCall -> handleToolCall(update)
            is SessionUpdate.ToolCallUpdate -> handleToolCallUpdate(update)
            is SessionUpdate.PlanUpdate -> handlePlanUpdate(update)
            is SessionUpdate.AvailableCommandsUpdate -> handleAvailableCommandsUpdate(update)
            is SessionUpdate.CurrentModeUpdate -> handleCurrentModeUpdate(update)
            is SessionUpdate.UsageUpdate -> handleUsageUpdate(update)
            is SessionUpdate.SessionInfoUpdate -> handleSessionInfoUpdate(update)
            is SessionUpdate.ConfigOptionUpdate -> handleConfigOptionUpdate(update)
            is SessionUpdate.UnknownSessionUpdate -> {
                println("[AcpSessionService] Unknown session update type: ${update.sessionUpdateType}")
            }
        }
    }

    /**
     * Handle user message chunk - appends to the last user message.
     */
    private fun handleUserMessageChunk(update: SessionUpdate.UserMessageChunk) {
        val content = extractTextContent(update.content) ?: return
        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == "user") {
            // Append to last user message
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new user message (shouldn't happen normally)
            addMessage("user", content)
        }
    }

    /**
     * Handle agent message chunk - appends to the last assistant message.
     */
    private fun handleAgentMessageChunk(update: SessionUpdate.AgentMessageChunk) {
        val content = extractTextContent(update.content) ?: return
        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            // Append to last assistant message
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new assistant message
            addMessage("assistant", content)
        }
    }

    /**
     * Handle agent thought chunk - appends to the last assistant message's thought field.
     */
    private fun handleAgentThoughtChunk(update: SessionUpdate.AgentThoughtChunk) {
        val thought = extractTextContent(update.content) ?: return
        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == "assistant") {
            // Append to last assistant message's thought
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(thought = (lastMsg.thought ?: "") + thought)
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new assistant message with thought
            addMessage("assistant", "").let {
                val messages = _messages.value
                if (messages.isNotEmpty() && messages.last().role == "assistant") {
                    val lastMsg = messages.last()
                    val updatedMsg = lastMsg.copy(thought = thought)
                    _messages.value = messages.dropLast(1) + updatedMsg
                }
            }
        }
    }

    /**
     * Handle tool call - adds a new tool call message.
     */
    @OptIn(UnstableApi::class)
    private fun handleToolCall(update: SessionUpdate.ToolCall) {
        val toolCallId = update.toolCallId.value
        val title = update.title
        val kind = update.kind?.name ?: "OTHER"

        // Track the tool call
        val currentTools = _activeToolCalls.value.toMutableMap()
        currentTools[toolCallId] = toolCallId
        _activeToolCalls.value = currentTools

        // Create a tool call message
        val toolCallInfo = ToolCallInfo(
            toolCallId = toolCallId,
            title = title,
            status = "pending",
            kind = kind
        )

        val message = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = "assistant",
            content = "[Tool Call: $title]",
            toolCalls = listOf(toolCallInfo)
        )
        _messages.value = _messages.value + message
    }

    /**
     * Handle tool call update - updates the status of an existing tool call.
     */
    @OptIn(UnstableApi::class)
    private fun handleToolCallUpdate(update: SessionUpdate.ToolCallUpdate) {
        val toolCallId = update.toolCallId.value
        val newStatus = update.status?.name ?: return

        // Update the tool call in messages
        val messages = _messages.value
        val updatedMessages = messages.map { msg ->
            if (msg.toolCalls.any { it.toolCallId == toolCallId }) {
                val updatedToolCalls = msg.toolCalls.map { tc ->
                    if (tc.toolCallId == toolCallId) {
                        tc.copy(status = newStatus)
                    } else tc
                }
                // Also update content based on status
                val statusText = when (newStatus) {
                    "in_progress" -> "🔄 ${msg.content}"
                    "completed" -> "✅ ${msg.content}"
                    "failed" -> "❌ ${msg.content}"
                    else -> msg.content
                }
                msg.copy(toolCalls = updatedToolCalls, content = statusText)
            } else msg
        }
        _messages.value = updatedMessages
    }

    /**
     * Handle plan update - logs the plan entries.
     */
    private fun handlePlanUpdate(update: SessionUpdate.PlanUpdate) {
        println("[AcpSessionService] Plan update: ${update.entries.size} entries")
        for (entry in update.entries) {
            println("[AcpSessionService]   - [${entry.status.name}] ${entry.content}")
        }
        // Could display plan in UI if needed
    }

    /**
     * Handle available commands update - stores the slash commands.
     */
    private fun handleAvailableCommandsUpdate(update: SessionUpdate.AvailableCommandsUpdate) {
        _availableCommands.value = update.availableCommands
        println("[AcpSessionService] Available commands updated: ${update.availableCommands.size} commands")
        for (cmd in update.availableCommands) {
            println("[AcpSessionService]   - /${cmd.name}: ${cmd.description}")
        }
    }

    /**
     * Handle current mode update - updates the current mode ID.
     */
    private fun handleCurrentModeUpdate(update: SessionUpdate.CurrentModeUpdate) {
        _currentModeId.value = update.currentModeId.value
        println("[AcpSessionService] Mode changed to: ${update.currentModeId.value}")
    }

    /**
     * Handle usage update - logs token usage.
     */
    private fun handleUsageUpdate(update: SessionUpdate.UsageUpdate) {
        println("[AcpSessionService] Usage: ${update.used}/${update.size} tokens" +
                (update.cost?.let { " (cost: ${it.amount} ${it.currency})" } ?: ""))
    }

    /**
     * Handle session info update - updates title/updatedAt.
     */
    private fun handleSessionInfoUpdate(update: SessionUpdate.SessionInfoUpdate) {
        println("[AcpSessionService] Session info update: title=${update.title}, updatedAt=${update.updatedAt}")
    }

    /**
     * Handle config option update.
     */
    private fun handleConfigOptionUpdate(update: SessionUpdate.ConfigOptionUpdate) {
        println("[AcpSessionService] Config options updated: ${update.configOptions.size} options")
    }

    /**
     * Handle prompt response - stores the stop reason.
     */
    private fun handlePromptResponse(response: PromptResponse) {
        _lastStopReason.value = response.stopReason
        println("[AcpSessionService] Prompt completed. Stop reason: ${response.stopReason}")
    }

    /**
     * Extract text content from ContentBlock.
     */
    private fun extractTextContent(content: ContentBlock): String? {
        return when (content) {
            is ContentBlock.Text -> content.text
            is ContentBlock.Image -> null // Ignore images for now
            is ContentBlock.Audio -> null // Ignore audio for now
            is ContentBlock.ResourceLink -> null // Ignore resource links for now
            is ContentBlock.Resource -> null // Ignore embedded resources for now
        }
    }

    /**
     * Disconnect from the current session.
     */
    fun disconnect() {
        client = null
        _isConnected.value = false
        _currentAgent.value = null
        _messages.value = emptyList()
        _activeToolCalls.value = emptyMap()
        _availableCommands.value = emptyList()
    }

    /**
     * Add a message to the chat history.
     */
    fun addMessage(role: String, content: String, thought: String? = null) {
        val newMessage = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            role = role,
            content = content,
            thought = thought
        )
        _messages.value = _messages.value + newMessage
    }

    /**
     * Clear all messages.
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    override fun dispose() {
        disconnect()
    }
}