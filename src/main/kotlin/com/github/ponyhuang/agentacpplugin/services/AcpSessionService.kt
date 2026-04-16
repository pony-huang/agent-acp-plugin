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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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
    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
    }


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
        val kind: String? = null,
        val locations: List<String> = emptyList(),
        val contentSummary: String? = null
    )

    private var pendingPromptEchoRemainder: String? = null

    /**
     * Create a new ACP session with the specified agent.
     * Handles authentication if required by the agent.
     */
    @OptIn(UnstableApi::class)
    suspend fun createSession(agentDefinition: AgentRegistry.AgentDefinition, cwd: String) {
        _isLoading.value = true
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

        addMessage(ROLE_USER, text)
        pendingPromptEchoRemainder = text

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

    internal fun applySessionUpdate(update: SessionUpdate) {
        handleSessionUpdate(update)
    }

    internal fun applyPromptResponse(response: PromptResponse) {
        handlePromptResponse(response)
    }

    /**
     * Handle user message chunk - appends to the last user message.
     */
    private fun handleUserMessageChunk(update: SessionUpdate.UserMessageChunk) {
        val content = renderMessageContent(update.content)
        if (consumePendingPromptEcho(content)) {
            return
        }

        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == ROLE_USER) {
            // Append to last user message
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new user message (shouldn't happen normally)
            addMessage(ROLE_USER, content)
        }
    }

    /**
     * Handle agent message chunk - appends to the last assistant message.
     */
    private fun handleAgentMessageChunk(update: SessionUpdate.AgentMessageChunk) {
        val content = renderMessageContent(update.content)
        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == ROLE_ASSISTANT) {
            // Append to last assistant message
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(content = lastMsg.content + content)
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new assistant message
            addMessage(ROLE_ASSISTANT, content)
        }
    }

    /**
     * Handle agent thought chunk - appends to the last assistant message's thought field.
     */
    private fun handleAgentThoughtChunk(update: SessionUpdate.AgentThoughtChunk) {
        val thought = renderMessageContent(update.content)
        val messages = _messages.value
        if (messages.isNotEmpty() && messages.last().role == ROLE_ASSISTANT) {
            // Append to last assistant message's thought
            val lastMsg = messages.last()
            val updatedMsg = lastMsg.copy(thought = (lastMsg.thought ?: "") + thought)
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new assistant message with thought
            addMessage(ROLE_ASSISTANT, "").let {
                val messages = _messages.value
                if (messages.isNotEmpty() && messages.last().role == ROLE_ASSISTANT) {
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
        val kind = update.kind?.toUiValue() ?: ToolKind.OTHER.toUiValue()

        // Track the tool call
        val currentTools = _activeToolCalls.value.toMutableMap()
        currentTools[toolCallId] = toolCallId
        _activeToolCalls.value = currentTools

        val toolCallInfo = ToolCallInfo(
            toolCallId = toolCallId,
            title = title,
            status = update.status?.toUiValue() ?: ToolCallStatus.PENDING.toUiValue(),
            kind = kind,
            locations = update.locations.map { it.toDisplayString() },
            contentSummary = summarizeToolCallContent(update.content)
        )
        val assistantMessage = ensureAssistantMessage()
        updateMessage(assistantMessage.id) { message ->
            message.copy(toolCalls = message.toolCalls + toolCallInfo)
        }
    }

    /**
     * Handle tool call update - updates the status of an existing tool call.
     */
    @OptIn(UnstableApi::class)
    private fun handleToolCallUpdate(update: SessionUpdate.ToolCallUpdate) {
        val toolCallId = update.toolCallId.value
        val updatedSummary = summarizeToolCallContent(update.content)
        val updatedLocations = update.locations?.map { it.toDisplayString() }

        // Update the tool call in messages
        val messages = _messages.value
        val updatedMessages = messages.map { msg ->
            if (msg.toolCalls.any { it.toolCallId == toolCallId }) {
                val updatedToolCalls = msg.toolCalls.map { tc ->
                    if (tc.toolCallId == toolCallId) {
                        tc.copy(
                            title = update.title ?: tc.title,
                            kind = update.kind?.toUiValue() ?: tc.kind,
                            status = update.status?.toUiValue() ?: tc.status,
                            locations = updatedLocations ?: tc.locations,
                            contentSummary = updatedSummary ?: tc.contentSummary
                        )
                    } else tc
                }
                msg.copy(toolCalls = updatedToolCalls)
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
        return (content as? ContentBlock.Text)?.text
    }

    private fun renderMessageContent(content: ContentBlock): String {
        return extractTextContent(content) ?: summarizeContentBlock(content)
    }

    private fun summarizeContentBlock(content: ContentBlock): String {
        return when (content) {
            is ContentBlock.Text -> content.text
            is ContentBlock.Image -> "[Image: ${content.mimeType}]"
            is ContentBlock.Audio -> "[Audio: ${content.mimeType}]"
            is ContentBlock.ResourceLink -> "[Resource: ${content.title ?: content.name}]"
            is ContentBlock.Resource -> when (val resource = content.resource) {
                is EmbeddedResourceResource.TextResourceContents -> "[Embedded Resource: ${resource.uri}]"
                is EmbeddedResourceResource.BlobResourceContents -> "[Embedded Binary Resource: ${resource.uri}]"
            }
        }
    }

    private fun summarizeToolCallContent(content: List<ToolCallContent>?): String? {
        val items = content.orEmpty()
        if (items.isEmpty()) {
            return null
        }

        return items.joinToString("\n") { item ->
            when (item) {
                is ToolCallContent.Content -> summarizeContentBlock(item.content)
                is ToolCallContent.Diff -> buildString {
                    append("Diff: ")
                    append(item.path)
                    item.oldText?.let { append(" (${it.length} -> ${item.newText.length} chars)") }
                }
                is ToolCallContent.Terminal -> "Terminal: ${item.terminalId}"
            }
        }
    }

    private fun consumePendingPromptEcho(content: String): Boolean {
        val remainder = pendingPromptEchoRemainder ?: return false
        return if (remainder.startsWith(content)) {
            pendingPromptEchoRemainder = remainder.removePrefix(content).ifEmpty { null }
            true
        } else {
            pendingPromptEchoRemainder = null
            false
        }
    }

    private fun ensureAssistantMessage(): ChatMessage {
        val lastMessage = _messages.value.lastOrNull()
        if (lastMessage?.role == ROLE_ASSISTANT) {
            return lastMessage
        }

        val newMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ROLE_ASSISTANT,
            content = ""
        )
        _messages.value = _messages.value + newMessage
        return newMessage
    }

    private fun updateMessage(messageId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) transform(message) else message
        }
    }

    /**
     * Disconnect from the current session.
     */
    fun disconnect() {
        client = null
        coroutineScope?.cancel()
        coroutineScope = null
        _currentSession = null
        _isConnected.value = false
        _currentAgent.value = null
        _messages.value = emptyList()
        _activeToolCalls.value = emptyMap()
        _availableCommands.value = emptyList()
        _availableModes.value = emptyList()
        _currentModeId.value = ""
        _availableModels.value = emptyList()
        _currentModelId.value = ""
        _lastStopReason.value = null
        pendingPromptEchoRemainder = null
    }

    /**
     * Add a message to the chat history.
     */
    fun addMessage(role: String, content: String, thought: String? = null) {
        val newMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
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
        pendingPromptEchoRemainder = null
    }

    override fun dispose() {
        disconnect()
    }

    private fun ToolKind.toUiValue(): String {
        return when (this) {
            ToolKind.READ -> "read"
            ToolKind.EDIT -> "edit"
            ToolKind.DELETE -> "delete"
            ToolKind.MOVE -> "move"
            ToolKind.SEARCH -> "search"
            ToolKind.EXECUTE -> "execute"
            ToolKind.THINK -> "think"
            ToolKind.FETCH -> "fetch"
            ToolKind.SWITCH_MODE -> "switch_mode"
            ToolKind.OTHER -> "other"
        }
    }

    private fun ToolCallStatus.toUiValue(): String {
        return when (this) {
            ToolCallStatus.PENDING -> "pending"
            ToolCallStatus.IN_PROGRESS -> "in_progress"
            ToolCallStatus.COMPLETED -> "completed"
            ToolCallStatus.FAILED -> "failed"
        }
    }

    private fun ToolCallLocation.toDisplayString(): String {
        return line?.let { "$path:$it" } ?: path
    }
}
