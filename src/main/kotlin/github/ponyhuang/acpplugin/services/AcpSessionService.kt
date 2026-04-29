package github.ponyhuang.acpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.toolwindow.AcpToolWindowPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
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
        private const val SESSION_CONNECT_TIMEOUT_MS = 15_000L
        private const val TOOL_STATUS_CANCELLED = "cancelled"
    }

    private val logger: Logger = Logger.getInstance(AcpSessionService::class.java)
    
    private val permissionRequestService = project.service<AcpPermissionRequestService>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private var activeClientToken: String? = null

    // Current agent definition (from our config, not from ACP agent)
    private var currentAgentDefinition: AgentRegistry.InstalledAgent? = null


    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

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

    private val _sessionTitle = MutableStateFlow<String?>(null)
    val sessionTitle: StateFlow<String?> = _sessionTitle.asStateFlow()

    private val _sessionUpdatedAt = MutableStateFlow<Long?>(null)
    val sessionUpdatedAt: StateFlow<Long?> = _sessionUpdatedAt.asStateFlow()

    private val _latestPlanEntries = MutableStateFlow<List<SessionPlanItem>>(emptyList())
    val latestPlanEntries: StateFlow<List<SessionPlanItem>> = _latestPlanEntries.asStateFlow()

    private val _latestUsage = MutableStateFlow<SessionUsageSummary?>(null)
    val latestUsage: StateFlow<SessionUsageSummary?> = _latestUsage.asStateFlow()

    private val _pendingPermissionRequests = MutableStateFlow<List<PermissionRequestInfo>>(emptyList())
    val pendingPermissionRequests: StateFlow<List<PermissionRequestInfo>> = _pendingPermissionRequests.asStateFlow()

    // ACP Client
    private var client: AcpAgentClient? = null
    private var coroutineScope: CoroutineScope? = null
    private var _currentSession: ClientSession? = null

    private data class ClientRuntime(
        val token: String,
        val client: AcpAgentClient,
        val scope: CoroutineScope,
        val agentDefinition: AgentRegistry.InstalledAgent,
        val agentInfo: AgentInfo
    )

    /**
     * Chat message model with optional metadata
     */
    data class ChatMessage(
        val id: String,
        val role: String,
        val content: String,
        val thought: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val toolCalls: List<ToolCallInfo> = emptyList(),
        val entries: List<MessageEntry> = legacyEntries(content, thought, toolCalls)
    )

    sealed interface MessageEntry {
        data class Content(val text: String) : MessageEntry
        data class Thought(val text: String) : MessageEntry
        data class ToolCall(val toolCall: ToolCallInfo) : MessageEntry
        data class PermissionRequest(val request: PermissionRequestInfo) : MessageEntry
    }

    /**
     * Tool call info for tracking tool execution
     */
    data class ToolCallInfo(
        val toolCallId: String,
        val title: String,
        val status: String = "pending",
        val kind: String? = null,
        val locations: List<ToolCallLocationInfo> = emptyList(),
        val contentSummary: String? = null,
        val failureDetails: String? = null,
        val diffContents: List<ToolCallDiffInfo> = emptyList()
    )

    data class ToolCallLocationInfo(
        val displayText: String,
        val path: String,
        val line: Int? = null
    )

    data class ToolCallDiffInfo(
        val path: String,
        val newText: String,
        val oldText: String?
    )

    data class SessionPlanItem(
        val content: String,
        val priority: String,
        val status: String
    )

    data class SessionUsageSummary(
        val usedTokens: Long,
        val totalTokens: Long,
        val costAmount: Double? = null,
        val costCurrency: String? = null
    )

    data class SessionListItem(
        val sessionId: String,
        val title: String?,
        val cwd: String,
        val updatedAtMillis: Long?,
    )

    data class PermissionRequestInfo(
        val requestId: String,
        val toolCallId: String,
        val title: String,
        val status: String = "in_progress",
        val options: List<PermissionOptionInfo>,
        val selectedOptionId: String?,
        val submitted: Boolean
    )

    data class PermissionOptionInfo(
        val optionId: String,
        val label: String,
        val kind: String?
    )

    private var pendingPromptEchoRemainder: String? = null

    init {
        serviceScope.launch {
            permissionRequestService.requests.collect { request ->
                val defaultSelection = request.permissions.firstOrNull()?.optionId?.value
                val requestInfo = PermissionRequestInfo(
                    requestId = request.requestId,
                    toolCallId = request.toolCall.toolCallId.value,
                    title = request.toolCall.title ?: MyBundle.message("permission.requestTitle"),
                    options = request.permissions.map { permission ->
                        PermissionOptionInfo(
                            optionId = permission.optionId.value,
                            label = permission.name,
                            kind = permission.kind.name.lowercase()
                        )
                    },
                    selectedOptionId = defaultSelection,
                    submitted = false
                )
                _pendingPermissionRequests.value = _pendingPermissionRequests.value + requestInfo
                val assistantMessage = ensureAssistantMessage()
                updateMessage(assistantMessage.id) { message ->
                    message.copy(
                        entries = message.entries + MessageEntry.PermissionRequest(requestInfo)
                    )
                }
            }
        }
    }

    /**
     * Create a new ACP session with the specified agent.
     * Handles authentication if required by the agent.
     */
    @OptIn(UnstableApi::class)
    suspend fun createSession(agentDefinition: AgentRegistry.InstalledAgent, cwd: String) {
        lifecycleMutex.withLock {
            val traceId = "create:${agentDefinition.id}"
            logger.info("[SessionLifecycle][$traceId] createSession start: cwd=$cwd, state=${sessionStateSnapshot()}")
            setLoadingState(true, "createSession:start:$traceId")
            setConnectingState(true, "createSession:start:$traceId")
            try {
                val reusingClient = shouldReuseCurrentClient(agentDefinition)
                logger.info("[SessionLifecycle][$traceId] createSession client reuse=$reusingClient")
                val activeClient = if (reusingClient) {
                    resetSessionData(
                        keepConnectedFlag = true,
                        clearAgentBinding = false,
                        reason = "createSession:reuse:$traceId"
                    )
                    requireNotNull(client)
                } else {
                    shutdownActiveClient()
                    resetSessionData(keepConnectedFlag = false, reason = "createSession:newClient:$traceId")
                    connectNewClient(agentDefinition).also { runtime ->
                        swapToNewClient(runtime, traceId, preserveConnectedState = false)
                    }.client
                }
                val info = requireNotNull(_currentAgent.value) {
                    "Agent '${agentDefinition.displayName}' did not complete ACP initialization."
                }

                _currentAgent.value = info

                logger.info("[SessionLifecycle][$traceId] Requesting new session from agent")
                val session = withTimeout(SESSION_CONNECT_TIMEOUT_MS) {
                    activeClient.newSession()
                } ?: throw IllegalStateException("Agent '${agentDefinition.displayName}' did not create a session.")

                _currentSession = session
                _availableModes.value = session.availableModes
                _availableModels.value = session.availableModels
                _currentModeId.value = session.currentMode.value.toString()
                _currentModelId.value = session.currentModel.value.toString()
                setConnectedState(true, "createSession:sessionCreated:$traceId")
                logger.info(
                    "[SessionLifecycle][$traceId] createSession success: sessionId=${session.sessionId.value}, " +
                        "modes=${session.availableModes.size}, models=${session.availableModels.size}, state=${sessionStateSnapshot()}"
                )

            } catch (t: TimeoutCancellationException) {
                handleSessionStartupTimeout(
                    operation = "creating",
                    agentDisplayName = agentDefinition.displayName,
                    reconnecting = false,
                    cause = t
                )
            } catch (t: Throwable) {
                logger.warn("Failed to create ACP session for agent ${agentDefinition.displayName}", t)
                disconnectLocked()
                throw t
            } finally {
                setLoadingState(false, "createSession:finally:$traceId")
                setConnectingState(false, "createSession:finally:$traceId")
                logger.info("[SessionLifecycle][$traceId] createSession end: state=${sessionStateSnapshot()}")
            }
        }
    }

    /**
     * Replace the current ACP session with a newly created session for another agent without
     * emitting an intermediate disconnected state to the UI.
     */
    @OptIn(UnstableApi::class)
    suspend fun replaceSession(agentDefinition: AgentRegistry.InstalledAgent, cwd: String) {
        lifecycleMutex.withLock {
            val traceId = "replace:${agentDefinition.id}"
            logger.info("[SessionLifecycle][$traceId] replaceSession start: cwd=$cwd, state=${sessionStateSnapshot()}")
            setLoadingState(true, "replaceSession:start:$traceId")
            setConnectingState(true, "replaceSession:start:$traceId")
            val previousRuntime = currentClientRuntime()
            val previousSession = _currentSession
            val previousConnected = _isConnected.value
            var stagedRuntime: ClientRuntime? = null
            try {
                val reusingClient = shouldReuseCurrentClient(agentDefinition)
                logger.info("[SessionLifecycle][$traceId] replaceSession client reuse=$reusingClient")
                val activeClient = if (reusingClient) {
                    requireNotNull(client)
                } else {
                    connectNewClient(agentDefinition).also { stagedRuntime = it }.client
                }
                val info = if (reusingClient) {
                    requireNotNull(_currentAgent.value) {
                        "Agent '${agentDefinition.displayName}' did not complete ACP initialization."
                    }
                } else {
                    requireNotNull(stagedRuntime).agentInfo
                }

                logger.info("[SessionLifecycle][$traceId] Requesting replacement session from agent")
                val session = withTimeout(SESSION_CONNECT_TIMEOUT_MS) {
                    activeClient.newSession()
                } ?: throw IllegalStateException("Agent '${agentDefinition.displayName}' did not create a session.")

                if (stagedRuntime != null) {
                    swapToNewClient(requireNotNull(stagedRuntime), traceId, preserveConnectedState = previousConnected)
                }
                clearSessionViewState(reason = "replaceSession:swap:$traceId")
                _currentSession = session
                _availableModes.value = session.availableModes
                _availableModels.value = session.availableModels
                _currentModeId.value = session.currentMode.value.toString()
                _currentModelId.value = session.currentModel.value.toString()
                currentAgentDefinition = agentDefinition
                _currentAgent.value = info
                setConnectedState(true, "replaceSession:sessionCreated:$traceId")
                logger.info(
                    "[SessionLifecycle][$traceId] replaceSession success: sessionId=${session.sessionId.value}, " +
                        "modes=${session.availableModes.size}, models=${session.availableModels.size}, state=${sessionStateSnapshot()}"
                )
                if (previousRuntime != null && stagedRuntime != null && previousRuntime.token != stagedRuntime.token) {
                    cleanupStaleClientAsync(previousRuntime, traceId)
                }

            } catch (t: TimeoutCancellationException) {
                cleanupFailedStagedRuntime(stagedRuntime, traceId)
                restoreReplaceFailureState(previousRuntime, previousSession, previousConnected, traceId)
                throw buildSessionConnectTimeoutException(agentDefinition.displayName, reconnecting = true).apply {
                    initCause(t)
                }
            } catch (t: Throwable) {
                logger.warn("Failed to replace ACP session with agent ${agentDefinition.displayName}", t)
                cleanupFailedStagedRuntime(stagedRuntime, traceId)
                restoreReplaceFailureState(previousRuntime, previousSession, previousConnected, traceId)
                throw t
            } finally {
                setLoadingState(false, "replaceSession:finally:$traceId")
                setConnectingState(false, "replaceSession:finally:$traceId")
                logger.info("[SessionLifecycle][$traceId] replaceSession end: state=${sessionStateSnapshot()}")
            }
        }
    }

    /**
     * Resume an existing ACP session with the specified session ID.
     */
    @OptIn(UnstableApi::class)
    suspend fun resumeSession(sessionId: String, agentDefinition: AgentRegistry.InstalledAgent, cwd: String) {
        lifecycleMutex.withLock {
            val traceId = "resume:${agentDefinition.id}:$sessionId"
            logger.info("[SessionLifecycle][$traceId] resumeSession start: cwd=$cwd, state=${sessionStateSnapshot()}")
            setLoadingState(true, "resumeSession:start:$traceId")
            setConnectingState(true, "resumeSession:start:$traceId")
            try {
                val activeClient = ensureConnectedClient(agentDefinition)
                resetSessionData(
                    keepConnectedFlag = true,
                    clearAgentBinding = false,
                    reason = "resumeSession:prepare:$traceId"
                )
                val info = requireNotNull(_currentAgent.value) {
                    "Agent '${agentDefinition.displayName}' did not complete ACP initialization."
                }

                if (info.capabilities.sessionCapabilities.resume == null) {
                    throw IllegalStateException("Agent '${agentDefinition.displayName}' does not support session resume.")
                }

                _currentAgent.value = info

                logger.info("[SessionLifecycle][$traceId] Requesting resume from agent")
                val session = withTimeout(SESSION_CONNECT_TIMEOUT_MS) {
                    activeClient.resumeSession(SessionId(sessionId))
                } ?: throw IllegalStateException("Agent '${agentDefinition.displayName}' did not resume session '$sessionId'.")

                _currentSession = session
                _availableModes.value = session.availableModes
                _availableModels.value = session.availableModels
                _currentModeId.value = session.currentMode.value.toString()
                _currentModelId.value = session.currentModel.value.toString()
                setConnectedState(true, "resumeSession:sessionCreated:$traceId")
                logger.info(
                    "[SessionLifecycle][$traceId] resumeSession success: sessionId=${session.sessionId.value}, " +
                        "modes=${session.availableModes.size}, models=${session.availableModels.size}, state=${sessionStateSnapshot()}"
                )
            } catch (t: TimeoutCancellationException) {
                handleSessionStartupTimeout(
                    operation = "resuming session $sessionId for",
                    agentDisplayName = agentDefinition.displayName,
                    reconnecting = true,
                    cause = t
                )
            } catch (t: Throwable) {
                logger.warn("Failed to resume ACP session $sessionId for agent ${agentDefinition.displayName}", t)
                disconnectLocked()
                throw t
            } finally {
                setLoadingState(false, "resumeSession:finally:$traceId")
                setConnectingState(false, "resumeSession:finally:$traceId")
                logger.info("[SessionLifecycle][$traceId] resumeSession end: state=${sessionStateSnapshot()}")
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun listSessions(
        agentDefinition: AgentRegistry.InstalledAgent,
        cwd: String
    ): List<SessionListItem> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            logger.info("[Sessions] listSessions entered: agent=${agentDefinition.displayName}, cwd=$cwd")
            if (canReuseClientForSessionListing(agentDefinition)) {
                logger.info("[Sessions] Reusing active client for ${agentDefinition.displayName}")
                val activeInfo = requireNotNull(_currentAgent.value) {
                    "Agent '${agentDefinition.displayName}' did not complete ACP initialization."
                }
                if (activeInfo.capabilities.sessionCapabilities.list == null) {
                    throw IllegalStateException("Agent '${agentDefinition.displayName}' does not support session listing.")
                }
                return@withLock listSessionsFromClient(
                    client = requireNotNull(client),
                    cwd = cwd,
                    agentDisplayName = agentDefinition.displayName
                )
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            logger.info("[Sessions] Creating temporary client bridge for ${agentDefinition.displayName}")
            val temporaryClient = createClientBridge(agentDefinition, scope)

            try {
                logger.info("[Sessions] Temporary client bridge created, connecting to ${agentDefinition.displayName}")
                val info = withTimeout(SESSION_CONNECT_TIMEOUT_MS) {
                    temporaryClient.connect()
                } ?: throw IllegalStateException("Agent '${agentDefinition.displayName}' did not complete ACP initialization.")

                logger.info("[Sessions] Agent connected, session list capability present=${info.capabilities.sessionCapabilities.list != null}")
                if (info.capabilities.sessionCapabilities.list == null) {
                    throw IllegalStateException("Agent '${agentDefinition.displayName}' does not support session listing.")
                }

                listSessionsFromClient(
                    client = temporaryClient,
                    cwd = cwd,
                    agentDisplayName = agentDefinition.displayName
                )
            } finally {
                logger.info("[Sessions] Closing temporary client bridge for ${agentDefinition.displayName}")
                try {
                    temporaryClient.close()
                } catch (t: Throwable) {
                    logger.warn("[Sessions] Failed to close temporary client bridge for ${agentDefinition.displayName}", t)
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun listSessionsFromClient(
        client: AcpAgentClient,
        cwd: String,
        agentDisplayName: String
    ): List<SessionListItem> {
        val listedSessions = client.listSessions(cwd).map { sessionInfo ->
            SessionListItem(
                sessionId = sessionInfo.sessionId.value,
                title = sessionInfo.title,
                cwd = sessionInfo.cwd,
                updatedAtMillis = parseUpdatedAt(sessionInfo.updatedAt)
            )
        }.sortedWith(
            compareByDescending<SessionListItem> { it.updatedAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.title.orEmpty() }
                .thenByDescending { it.sessionId }
        )
        logger.info("[Sessions] Agent returned ${listedSessions.size} sessions for cwd=$cwd ($agentDisplayName)")
        return listedSessions
    }

    internal fun buildSessionConnectTimeoutException(
        agentDisplayName: String,
        reconnecting: Boolean
    ): IllegalStateException {
        val action = if (reconnecting) "reconnecting" else "connecting"
        return IllegalStateException(
            "Timed out while $action to '$agentDisplayName'. Check whether the agent is installed and can start in ACP mode."
        )
    }

    private suspend fun handleSessionStartupTimeout(
        operation: String,
        agentDisplayName: String,
        reconnecting: Boolean,
        cause: TimeoutCancellationException
    ): Nothing {
        logger.warn(
            "Timed out while $operation ACP session for agent $agentDisplayName after $SESSION_CONNECT_TIMEOUT_MS ms"
        )
        disconnectLocked()
        throw buildSessionConnectTimeoutException(agentDisplayName, reconnecting).apply {
            initCause(cause)
        }
    }

    /**
     * Send a prompt to the current session.
     */
    @OptIn(UnstableApi::class)
    suspend fun sendPrompt(text: String) {
        val session = _currentSession ?: return

        // Guard against concurrent prompt submissions
        if (_isLoading.value) {
            logger.warn("Prompt submission ignored - another prompt is already in progress")
            return
        }

        logger.info("[SessionLifecycle][prompt] sendPrompt start: chars=${text.length}, state=${sessionStateSnapshot()}")
        setLoadingState(true, "sendPrompt:start")
        _lastStopReason.value = null
        addMessage(ROLE_USER, text)
        ensureAssistantMessage()
        updateDerivedSessionTitleFromPrompt(text)
        pendingPromptEchoRemainder = text

        try {
            // Collect streaming updates via Flow
            val content = listOf(ContentBlock.Text(text))
            session.prompt(content).collect { event ->
                when (event) {
                    is Event.SessionUpdateEvent -> handleSessionUpdate(event.update)
                    is Event.PromptResponseEvent -> handlePromptResponse(event.response)
                }
            }
        } finally {
            setLoadingState(false, "sendPrompt:finally")
            logger.info("[SessionLifecycle][prompt] sendPrompt end: state=${sessionStateSnapshot()}")
        }
    }

    /**
     * Cancel the current ongoing prompt operation.
     */
    @OptIn(UnstableApi::class)
    suspend fun cancel() {
        if (!_isLoading.value) {
            return
        }
        val session = _currentSession ?: return
        logger.info("[SessionLifecycle][prompt] cancel start: state=${sessionStateSnapshot()}")
        session.cancel()
        markActiveToolCallsCancelled()
        _lastStopReason.value = StopReason.CANCELLED
        _sessionUpdatedAt.value = System.currentTimeMillis()
        setLoadingState(false, "cancel:completed")
        logger.info("[SessionLifecycle][prompt] cancel end: state=${sessionStateSnapshot()}")
    }

    /**
     * Set the current session mode.
     * Calls the SDK to actually change the mode on the agent side.
     */
    @OptIn(UnstableApi::class)
    suspend fun setMode(modeId: String) {
        val session = _currentSession ?: return
        logger.info("[AcpSessionService] Setting mode to: $modeId")
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
        logger.info("[AcpSessionService] Setting model to: $modelId")
        session.setModel(ModelId(modelId))
        // Model change will be confirmed via notification
    }

    /**
     * Complete SessionUpdate handler - handles all update types.
     */
    @OptIn(UnstableApi::class)
    private fun handleSessionUpdate(update: SessionUpdate) {
        logger.info("[AcpSessionService] Received update: $update")
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
                logger.error("[AcpSessionService] Unknown session update type: ${update.sessionUpdateType}")
            }
        }
    }

    private fun handleSessionUpdateFromClient(clientToken: String, update: SessionUpdate) {
        if (!isCurrentClientToken(clientToken)) {
            logger.info("[SessionLifecycle] Ignoring stale session update from token=$clientToken")
            return
        }
        handleSessionUpdate(update)
    }

    internal fun applySessionUpdate(update: SessionUpdate) {
        handleSessionUpdate(update)
    }

    internal fun applyPromptResponse(response: PromptResponse) {
        handlePromptResponse(response)
    }

    private fun handlePromptResponseFromClient(clientToken: String, response: PromptResponse) {
        if (!isCurrentClientToken(clientToken)) {
            logger.info("[SessionLifecycle] Ignoring stale prompt response from token=$clientToken")
            return
        }
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
            val updatedMsg = lastMsg.copy(
                content = lastMsg.content + content,
                entries = appendTextEntry(lastMsg.entries, content, isThought = false)
            )
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
            val updatedMsg = lastMsg.copy(
                content = lastMsg.content + content,
                entries = appendTextEntry(lastMsg.entries, content, isThought = false)
            )
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
            val updatedMsg = lastMsg.copy(
                thought = (lastMsg.thought ?: "") + thought,
                entries = appendTextEntry(lastMsg.entries, thought, isThought = true)
            )
            _messages.value = messages.dropLast(1) + updatedMsg
        } else {
            // Create new assistant message with thought
            addMessage(ROLE_ASSISTANT, "").let {
                val messages = _messages.value
                if (messages.isNotEmpty() && messages.last().role == ROLE_ASSISTANT) {
                    val lastMsg = messages.last()
                    val updatedMsg = lastMsg.copy(
                        thought = thought,
                        entries = appendTextEntry(lastMsg.entries, thought, isThought = true)
                    )
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
        val status = update.status?.toUiValue() ?: ToolCallStatus.PENDING.toUiValue()

        trackToolCallStatus(toolCallId, status)

        val toolCallInfo = ToolCallInfo(
            toolCallId = toolCallId,
            title = title,
            status = status,
            kind = kind,
            locations = update.locations.map { it.toInfo() },
            contentSummary = summaryForTool(kind, update.content),
            failureDetails = failureDetailsFor(status, update.content, update.rawOutput),
            diffContents = extractDiffToolCallContent(update.content)
        )
        val assistantMessage = ensureAssistantMessage()
        updateMessage(assistantMessage.id) { message ->
            message.copy(
                toolCalls = message.toolCalls + toolCallInfo,
                entries = message.entries + MessageEntry.ToolCall(toolCallInfo)
            )
        }
    }

    /**
     * Handle tool call update - updates the status of an existing tool call.
     */
    @OptIn(UnstableApi::class)
    private fun handleToolCallUpdate(update: SessionUpdate.ToolCallUpdate) {
        val toolCallId = update.toolCallId.value
        val updatedStatus = update.status?.toUiValue()
        val updatedSummary = summarizeToolCallContent(update.content)
        val updatedLocations = update.locations?.map { it.toInfo() }
        val incomingDiffContents = extractDiffToolCallContent(update.content)
        updatedStatus?.let { trackToolCallStatus(toolCallId, it) }

        // Update the tool call in messages
        val messages = _messages.value
        val updatedMessages = messages.map { msg ->
            if (msg.toolCalls.any { it.toolCallId == toolCallId } ||
                msg.entries.any { entry ->
                    entry is MessageEntry.PermissionRequest && entry.request.toolCallId == toolCallId
                }
            ) {
                val updatedToolCalls = msg.toolCalls.map { tc ->
                    if (tc.toolCallId == toolCallId) {
                        val nextKind = update.kind?.toUiValue() ?: tc.kind
                        val nextStatus = updatedStatus ?: tc.status
                        tc.copy(
                            title = update.title ?: tc.title,
                            kind = nextKind,
                            status = nextStatus,
                            locations = updatedLocations ?: tc.locations,
                            contentSummary = mergeSummary(nextKind, updatedSummary, tc.contentSummary),
                            failureDetails = mergeFailureDetails(
                                nextStatus,
                                failureDetailsFor(nextStatus, update.content, update.rawOutput),
                                tc.failureDetails
                            ),
                            diffContents = mergeDiffContents(tc.diffContents, incomingDiffContents)
                        )
                    } else tc
                }
                val updatedEntries = msg.entries.map { entry ->
                    when (entry) {
                        is MessageEntry.ToolCall -> {
                            if (entry.toolCall.toolCallId == toolCallId) {
                                val nextKind = update.kind?.toUiValue() ?: entry.toolCall.kind
                                val nextStatus = updatedStatus ?: entry.toolCall.status
                                entry.copy(
                                    toolCall = entry.toolCall.copy(
                                        title = update.title ?: entry.toolCall.title,
                                        kind = nextKind,
                                        status = nextStatus,
                                        locations = updatedLocations ?: entry.toolCall.locations,
                                        contentSummary = mergeSummary(nextKind, updatedSummary, entry.toolCall.contentSummary),
                                        failureDetails = mergeFailureDetails(
                                            nextStatus,
                                            failureDetailsFor(nextStatus, update.content, update.rawOutput),
                                            entry.toolCall.failureDetails
                                        ),
                                        diffContents = mergeDiffContents(entry.toolCall.diffContents, incomingDiffContents)
                                    )
                                )
                            } else {
                                entry
                            }
                        }
                        is MessageEntry.PermissionRequest -> {
                            val updatedTitle = update.title
                            if (entry.request.toolCallId == toolCallId && updatedTitle != null) {
                                entry.copy(request = entry.request.copy(title = updatedTitle))
                            } else {
                                entry
                            }
                        }
                        else -> entry
                    }
                }
                msg.copy(toolCalls = updatedToolCalls, entries = updatedEntries)
            } else msg
        }
        _messages.value = updatedMessages
    }

    private fun mergeDiffContents(
        existing: List<ToolCallDiffInfo>,
        incoming: List<ToolCallDiffInfo>
    ): List<ToolCallDiffInfo> {
        if (incoming.isEmpty()) {
            return existing
        }

        val merged = existing.associateByTo(linkedMapOf(), ToolCallDiffInfo::path).toMutableMap()
        incoming.forEach { next ->
            val current = merged[next.path]
            merged[next.path] = if (current == null) {
                next
            } else {
                current.copy(
                    newText = next.newText,
                    oldText = next.oldText ?: current.oldText
                )
            }
        }
        return merged.values.toList()
    }

    /**
     * Handle plan update - logs the plan entries.
     */
    private fun handlePlanUpdate(update: SessionUpdate.PlanUpdate) {
        _latestPlanEntries.value = update.entries.map { entry ->
            SessionPlanItem(
                content = entry.content,
                priority = entry.priority.toUiValue(),
                status = entry.status.toUiValue()
            )
        }
        logger.info("[AcpSessionService] Plan update: ${update.entries.size} entries")
        for (entry in update.entries) {
            logger.info("[AcpSessionService]   - [${entry.status.name}] ${entry.content}")
        }
    }

    /**
     * Handle available commands update - stores the slash commands.
     */
    private fun handleAvailableCommandsUpdate(update: SessionUpdate.AvailableCommandsUpdate) {
        _availableCommands.value = update.availableCommands
        logger.info("[AcpSessionService] Available commands updated: ${update.availableCommands.size} commands")
        for (cmd in update.availableCommands) {
            logger.info("[AcpSessionService]   - /${cmd.name}: ${cmd.description}")
        }
    }

    /**
     * Handle current mode update - updates the current mode ID.
     */
    private fun handleCurrentModeUpdate(update: SessionUpdate.CurrentModeUpdate) {
        _currentModeId.value = update.currentModeId.value
        logger.info("[AcpSessionService] Mode changed to: ${update.currentModeId.value}")
    }

    /**
     * Handle usage update - logs token usage.
     */
    @OptIn(UnstableApi::class)
    private fun handleUsageUpdate(update: SessionUpdate.UsageUpdate) {
        _latestUsage.value = SessionUsageSummary(
            usedTokens = update.used,
            totalTokens = update.size,
            costAmount = update.cost?.amount,
            costCurrency = update.cost?.currency
        )
        logger.info("[AcpSessionService] Usage: ${update.used}/${update.size} tokens" +
                (update.cost?.let { " (cost: ${it.amount} ${it.currency})" } ?: ""))
    }

    /**
     * Handle session info update - updates title/updatedAt.
     */
    private fun handleSessionInfoUpdate(update: SessionUpdate.SessionInfoUpdate) {
        update.title?.let {
            _sessionTitle.value = it
        }
        update.updatedAt?.let { _sessionUpdatedAt.value = parseUpdatedAt(it) }
        logger.info("[AcpSessionService] Session info update: title=${update.title}, updatedAt=${update.updatedAt}")
    }

    /**
     * Handle config option update.
     */
    private fun handleConfigOptionUpdate(update: SessionUpdate.ConfigOptionUpdate) {
        logger.info("[AcpSessionService] Config options updated: ${update.configOptions.size} options")
    }

    /**
     * Handle prompt response - stores the stop reason.
     */
    private fun handlePromptResponse(response: PromptResponse) {
        if (response.stopReason == StopReason.CANCELLED) {
            markActiveToolCallsCancelled()
        }
        _lastStopReason.value = response.stopReason
        _sessionUpdatedAt.value = System.currentTimeMillis()
        logger.info("[AcpSessionService] Prompt completed. Stop reason: ${response.stopReason}")
    }

    private fun trackToolCallStatus(toolCallId: String, status: String) {
        val currentTools = _activeToolCalls.value.toMutableMap()
        if (status.isTerminalToolStatus()) {
            currentTools.remove(toolCallId)
        } else {
            currentTools[toolCallId] = toolCallId
        }
        _activeToolCalls.value = currentTools
    }

    private fun markActiveToolCallsCancelled() {
        val activeToolCallIds = _activeToolCalls.value.keys
        if (activeToolCallIds.isEmpty()) {
            return
        }

        _messages.value = _messages.value.map { message ->
            val updatedToolCalls = message.toolCalls.map { toolCall ->
                if (toolCall.toolCallId in activeToolCallIds && !toolCall.status.isTerminalToolStatus()) {
                    toolCall.copy(status = TOOL_STATUS_CANCELLED)
                } else {
                    toolCall
                }
            }
            val updatedEntries = message.entries.map { entry ->
                if (entry is MessageEntry.ToolCall &&
                    entry.toolCall.toolCallId in activeToolCallIds &&
                    !entry.toolCall.status.isTerminalToolStatus()
                ) {
                    entry.copy(toolCall = entry.toolCall.copy(status = TOOL_STATUS_CANCELLED))
                } else {
                    entry
                }
            }
            message.copy(toolCalls = updatedToolCalls, entries = updatedEntries)
        }
        _activeToolCalls.value = emptyMap()
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

    private fun extractDiffToolCallContent(content: List<ToolCallContent>?): List<ToolCallDiffInfo> {
        return content.orEmpty().mapNotNull { item ->
            when (item) {
                is ToolCallContent.Diff -> ToolCallDiffInfo(
                    path = item.path,
                    newText = item.newText,
                    oldText = item.oldText
                )
                else -> null
            }
        }
    }

    private fun summaryForTool(kind: String?, content: List<ToolCallContent>?): String? {
        if (kind.isReadToolKind()) {
            return null
        }
        return summarizeToolCallContent(content)
    }

    private fun mergeSummary(kind: String?, updatedSummary: String?, existingSummary: String?): String? {
        if (kind.isReadToolKind()) {
            return null
        }
        return updatedSummary ?: existingSummary
    }

    private fun failureDetailsFor(
        status: String?,
        content: List<ToolCallContent>?,
        rawOutput: JsonElement?
    ): String? {
        if (status != "failed") {
            return null
        }
        return summarizeToolCallContent(content)?.takeIf { it.isNotBlank() }
            ?: summarizeRawOutput(rawOutput)
    }

    private fun mergeFailureDetails(status: String, updated: String?, existing: String?): String? {
        if (status != "failed") {
            return null
        }
        return updated ?: existing
    }

    private fun summarizeRawOutput(rawOutput: JsonElement?): String? {
        val text = rawOutput?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == "null") {
            return null
        }
        return if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            text.substring(1, text.length - 1)
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            text
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

    private fun appendTextEntry(
        entries: List<MessageEntry>,
        text: String,
        isThought: Boolean
    ): List<MessageEntry> {
        if (text.isEmpty()) {
            return entries
        }

        val lastEntry = entries.lastOrNull()
        return when {
            isThought && lastEntry is MessageEntry.Thought ->
                entries.dropLast(1) + lastEntry.copy(text = lastEntry.text + text)
            !isThought && lastEntry is MessageEntry.Content ->
                entries.dropLast(1) + lastEntry.copy(text = lastEntry.text + text)
            isThought -> entries + MessageEntry.Thought(text)
            else -> entries + MessageEntry.Content(text)
        }
    }

    private fun updateDerivedSessionTitleFromPrompt(text: String) {
        if (_sessionTitle.value.isNullOrBlank()) {
            val title = text.take(50).ifBlank { null }
            _sessionTitle.value = title
        }
        _sessionUpdatedAt.value = System.currentTimeMillis()
    }

    private fun parseUpdatedAt(value: String?): Long? {
        if (value == null) {
            return null
        }
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun resetDerivedSessionState() {
        _sessionTitle.value = null
        _sessionUpdatedAt.value = null
        _latestPlanEntries.value = emptyList()
        _latestUsage.value = null
        _lastStopReason.value = null
        _pendingPermissionRequests.value = emptyList()
        pendingPromptEchoRemainder = null
    }

    private fun clearSessionViewState(reason: String) {
        logger.info("[SessionLifecycle] clearSessionViewState start: reason=$reason, before=${sessionStateSnapshot()}")
        _currentSession = null
        _messages.value = emptyList()
        _activeToolCalls.value = emptyMap()
        _availableCommands.value = emptyList()
        _availableModes.value = emptyList()
        _currentModeId.value = ""
        _availableModels.value = emptyList()
        _currentModelId.value = ""
        resetDerivedSessionState()
        logger.info("[SessionLifecycle] clearSessionViewState end: reason=$reason, after=${sessionStateSnapshot()}")
    }

    /**
     * Disconnect from the current session.
     */
    suspend fun disconnect() {
        lifecycleMutex.withLock {
            logger.info("[SessionLifecycle] disconnect requested: state=${sessionStateSnapshot()}")
            disconnectLocked()
        }
    }

    private fun disconnectLocked() {
        logger.info("[SessionLifecycle] disconnectLocked start: state=${sessionStateSnapshot()}")
        shutdownActiveClient()
        resetSessionData(keepConnectedFlag = false, reason = "disconnectLocked")
        logger.info("[SessionLifecycle] disconnectLocked end: state=${sessionStateSnapshot()}")
    }

    fun currentAgentId(): String? = currentAgentDefinition?.id

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
        _sessionTitle.value = null
        _sessionUpdatedAt.value = null
        _latestPlanEntries.value = emptyList()
        _latestUsage.value = null
        _lastStopReason.value = null
        _pendingPermissionRequests.value = emptyList()
        pendingPromptEchoRemainder = null
    }

    fun submitPermissionRequest(requestId: String, optionId: String): Boolean {
        val submitted = permissionRequestService.submitSelection(requestId, PermissionOptionId(optionId))
        if (!submitted) {
            return false
        }

        val updatedRequests = _pendingPermissionRequests.value.map { request ->
            if (request.requestId == requestId) {
                request.copy(
                    status = "completed",
                    selectedOptionId = optionId,
                    submitted = true
                )
            } else {
                request
            }
        }
        _pendingPermissionRequests.value = updatedRequests

        _messages.value = _messages.value.map { message ->
            var changed = false
            val updatedEntries = message.entries.map { entry ->
                when {
                    entry is MessageEntry.PermissionRequest && entry.request.requestId == requestId -> {
                        changed = true
                        val updatedRequest = updatedRequests.firstOrNull { it.requestId == requestId } ?: entry.request
                        entry.copy(request = updatedRequest)
                    }
                    else -> entry
                }
            }
            if (!changed) {
                message
            } else {
                message.copy(entries = updatedEntries)
            }
        }
        return true
    }

    override fun dispose() {
        serviceScope.cancel()
        disconnectLocked()
    }

    private fun shutdownActiveClient() {
        logger.info("[SessionLifecycle] shutdownActiveClient start: state=${sessionStateSnapshot()}")
        activeClientToken = null
        client?.close()
        client = null
        coroutineScope?.cancel()
        coroutineScope = null
        _currentSession = null
        logger.info("[SessionLifecycle] shutdownActiveClient end: state=${sessionStateSnapshot()}")
    }

    private suspend fun ensureConnectedClient(agentDefinition: AgentRegistry.InstalledAgent): AcpAgentClient {
        logger.info(
            "[SessionLifecycle] ensureConnectedClient: target=${agentDefinition.id}, reuse=${shouldReuseCurrentClient(agentDefinition)}, " +
                "state=${sessionStateSnapshot()}"
        )
        if (shouldReuseCurrentClient(agentDefinition)) {
            currentAgentDefinition = agentDefinition
            return requireNotNull(client)
        }

        shutdownActiveClient()
        return connectNewClient(agentDefinition).also { runtime ->
            swapToNewClient(runtime, "ensureConnectedClient:${agentDefinition.id}", preserveConnectedState = false)
        }.client
    }

    private suspend fun connectNewClient(agentDefinition: AgentRegistry.InstalledAgent): ClientRuntime {
        logger.info("[SessionLifecycle] connectNewClient start: target=${agentDefinition.id}, state=${sessionStateSnapshot()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val token = UUID.randomUUID().toString()
        val newClient = createClientBridge(agentDefinition, scope, token)
        val info = withTimeout(SESSION_CONNECT_TIMEOUT_MS) {
            newClient.connect()
        } ?: throw IllegalStateException("Agent '${agentDefinition.displayName}' did not complete ACP initialization.")
        logger.info(
            "[SessionLifecycle] connectNewClient success: target=${agentDefinition.id}, " +
                "agentInfo=${info.implementation?.name ?: "<none>"}, state=${sessionStateSnapshot()}"
        )
        return ClientRuntime(
            token = token,
            client = newClient,
            scope = scope,
            agentDefinition = agentDefinition,
            agentInfo = info
        )
    }

    private fun createClientBridge(
        agentDefinition: AgentRegistry.InstalledAgent,
        scope: CoroutineScope,
        clientToken: String? = null
    ): AcpAgentClient {
        val configService = project.service<AcpAgentsConfigService>()
        val sessionUpdateHandler: suspend (SessionUpdate) -> Unit = { update ->
            if (clientToken == null) {
                handleSessionUpdate(update)
            } else {
                handleSessionUpdateFromClient(clientToken, update)
            }
        }
        val permissionRequestHandler: suspend (SessionUpdate.ToolCallUpdate, List<PermissionOption>, JsonElement?) -> RequestPermissionResponse =
            { toolCall, permissions, meta ->
                if (permissionRequestService.hasActiveSubscribers()) {
                    permissionRequestService.requestPermissions(toolCall, permissions, meta)
                } else {
                    autoApprovePermissions(permissions, meta)
                }
            }
        return configService.createClientBridge(
            agentDefinition.displayName,
            scope,
            sessionUpdateHandler,
            permissionRequestHandler
        ) ?: throw IllegalStateException("Agent '${agentDefinition.displayName}' is not configured.")
    }

    private fun swapToNewClient(
        runtime: ClientRuntime,
        traceId: String,
        preserveConnectedState: Boolean
    ) {
        val previous = currentClientRuntime()
        activeClientToken = runtime.token
        client = runtime.client
        coroutineScope = runtime.scope
        currentAgentDefinition = runtime.agentDefinition
        _currentAgent.value = runtime.agentInfo
        if (!preserveConnectedState) {
            setConnectedState(false, "swapToNewClient:prepare:$traceId")
        }
        logger.info(
            "[SessionLifecycle][$traceId] client swap success: previous=${previous?.agentDefinition?.id ?: "<none>"} -> " +
                "${runtime.agentDefinition.id}, token=${runtime.token}"
        )
    }

    private fun cleanupStaleClientAsync(runtime: ClientRuntime, traceId: String) {
        serviceScope.launch {
            logger.info(
                "[SessionLifecycle][$traceId] stale client cleanup start: token=${runtime.token}, agent=${runtime.agentDefinition.id}"
            )
            try {
                runtime.client.close()
                logger.info(
                    "[SessionLifecycle][$traceId] stale client cleanup end: token=${runtime.token}, agent=${runtime.agentDefinition.id}"
                )
            } catch (t: Throwable) {
                logger.warn(
                    "[SessionLifecycle][$traceId] stale client cleanup failed: token=${runtime.token}, agent=${runtime.agentDefinition.id}",
                    t
                )
            } finally {
                runtime.scope.cancel()
            }
        }
    }

    private fun cleanupFailedStagedRuntime(runtime: ClientRuntime?, traceId: String) {
        if (runtime == null) {
            return
        }
        logger.info(
            "[SessionLifecycle][$traceId] staged client cleanup start: token=${runtime.token}, agent=${runtime.agentDefinition.id}"
        )
        try {
            runtime.client.close()
            logger.info(
                "[SessionLifecycle][$traceId] staged client cleanup end: token=${runtime.token}, agent=${runtime.agentDefinition.id}"
            )
        } catch (t: Throwable) {
            logger.warn(
                "[SessionLifecycle][$traceId] staged client cleanup failed: token=${runtime.token}, agent=${runtime.agentDefinition.id}",
                t
            )
        } finally {
            runtime.scope.cancel()
        }
    }

    private fun restoreReplaceFailureState(
        previousRuntime: ClientRuntime?,
        previousSession: ClientSession?,
        previousConnected: Boolean,
        traceId: String
    ) {
        if (previousRuntime == null) {
            logger.info("[SessionLifecycle][$traceId] replaceSession failed with no previous runtime; disconnecting")
            resetSessionData(keepConnectedFlag = false, reason = "replaceSession:restoreFailure:$traceId")
            return
        }
        activeClientToken = previousRuntime.token
        client = previousRuntime.client
        coroutineScope = previousRuntime.scope
        currentAgentDefinition = previousRuntime.agentDefinition
        _currentAgent.value = previousRuntime.agentInfo
        _currentSession = previousSession
        setConnectedState(previousConnected && previousSession != null, "replaceSession:restoreFailure:$traceId")
        logger.info(
            "[SessionLifecycle][$traceId] restore previous client after replace failure: agent=${previousRuntime.agentDefinition.id}, " +
                "sessionId=${previousSession?.sessionId?.value ?: "<none>"}"
        )
    }

    private fun currentClientRuntime(): ClientRuntime? {
        val activeClient = client ?: return null
        val activeScope = coroutineScope ?: return null
        val agentDefinition = currentAgentDefinition ?: return null
        val agentInfo = _currentAgent.value ?: return null
        val token = activeClientToken ?: return null
        return ClientRuntime(token, activeClient, activeScope, agentDefinition, agentInfo)
    }

    private fun isCurrentClientToken(clientToken: String): Boolean = activeClientToken == clientToken

    private fun shouldReuseCurrentClient(agentDefinition: AgentRegistry.InstalledAgent): Boolean {
        val currentAgentId = currentAgentDefinition?.id
        return client != null && currentAgentId == agentDefinition.id
    }

    private fun canReuseClientForSessionListing(agentDefinition: AgentRegistry.InstalledAgent): Boolean {
        return shouldReuseCurrentClient(agentDefinition) && !_isLoading.value && !_isConnecting.value
    }

    private fun resetSessionData(
        keepConnectedFlag: Boolean,
        clearAgentBinding: Boolean
    ) {
        resetSessionData(keepConnectedFlag, clearAgentBinding, "unspecified")
    }

    private fun resetSessionData(
        keepConnectedFlag: Boolean,
        clearAgentBinding: Boolean = true,
        reason: String = "unspecified"
    ) {
        logger.info(
            "[SessionLifecycle] resetSessionData start: keepConnectedFlag=$keepConnectedFlag, " +
                "clearAgentBinding=$clearAgentBinding, reason=$reason, before=${sessionStateSnapshot()}"
        )
        if (!keepConnectedFlag) {
            setConnectedState(false, "resetSessionData:$reason")
        }
        _currentSession = null
        if (clearAgentBinding) {
            _currentAgent.value = null
            currentAgentDefinition = null
        }
        clearSessionViewState(reason)
        logger.info("[SessionLifecycle] resetSessionData end: reason=$reason, after=${sessionStateSnapshot()}")
    }

    private fun autoApprovePermissions(
        permissions: List<PermissionOption>,
        meta: JsonElement?,
    ): RequestPermissionResponse {
        val selectedOption = permissions.firstOrNull {
            it.kind == PermissionOptionKind.ALLOW_ALWAYS || it.kind == PermissionOptionKind.ALLOW_ONCE
        } ?: permissions.firstOrNull()

        return if (selectedOption != null) {
            RequestPermissionResponse(
                RequestPermissionOutcome.Selected(selectedOption.optionId),
                meta
            )
        } else {
            RequestPermissionResponse(
                RequestPermissionOutcome.Cancelled,
                meta
            )
        }
    }

    private fun setLoadingState(value: Boolean, reason: String) {
        if (_isLoading.value != value) {
            logger.info("[SessionLifecycle] isLoading ${_isLoading.value} -> $value ($reason)")
        }
        _isLoading.value = value
    }

    private fun setConnectingState(value: Boolean, reason: String) {
        if (_isConnecting.value != value) {
            logger.info("[SessionLifecycle] isConnecting ${_isConnecting.value} -> $value ($reason)")
        }
        _isConnecting.value = value
    }

    private fun setConnectedState(value: Boolean, reason: String) {
        if (_isConnected.value != value) {
            logger.info("[SessionLifecycle] isConnected ${_isConnected.value} -> $value ($reason)")
        }
        _isConnected.value = value
    }

    private fun sessionStateSnapshot(): String {
        val sessionId = _currentSession?.sessionId?.value ?: "<none>"
        val currentAgentId = currentAgentDefinition?.id ?: "<none>"
        val currentAgentName = currentAgentDefinition?.displayName ?: "<none>"
        return "connected=${_isConnected.value}, loading=${_isLoading.value}, connecting=${_isConnecting.value}, " +
            "currentAgentId=$currentAgentId, currentAgentName=$currentAgentName, sessionId=$sessionId, " +
            "messages=${_messages.value.size}, commands=${_availableCommands.value.size}, " +
            "modes=${_availableModes.value.size}, models=${_availableModels.value.size}"
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

    private fun ToolCallLocation.toInfo(): ToolCallLocationInfo {
        val lineNumber = line?.toInt()
        val displayText = lineNumber?.let { "$path:$it" } ?: path
        return ToolCallLocationInfo(
            displayText = displayText,
            path = path,
            line = lineNumber
        )
    }

    private fun PlanEntryPriority.toUiValue(): String {
        return when (this) {
            PlanEntryPriority.HIGH -> "high"
            PlanEntryPriority.MEDIUM -> "medium"
            PlanEntryPriority.LOW -> "low"
        }
    }

    private fun PlanEntryStatus.toUiValue(): String {
        return when (this) {
            PlanEntryStatus.PENDING -> "pending"
            PlanEntryStatus.IN_PROGRESS -> "in_progress"
            PlanEntryStatus.COMPLETED -> "completed"
        }
    }

    private fun String.isTerminalToolStatus(): Boolean {
        return this == "completed" || this == "failed" || this == TOOL_STATUS_CANCELLED
    }

    private fun String?.isReadToolKind(): Boolean = this == "read"
}

private fun legacyEntries(
    content: String,
    thought: String?,
    toolCalls: List<AcpSessionService.ToolCallInfo>
): List<AcpSessionService.MessageEntry> = buildList {
    thought?.takeIf { it.isNotEmpty() }?.let { add(AcpSessionService.MessageEntry.Thought(it)) }
    toolCalls.forEach { add(AcpSessionService.MessageEntry.ToolCall(it)) }
    content.takeIf { it.isNotEmpty() }?.let { add(AcpSessionService.MessageEntry.Content(it)) }
}
