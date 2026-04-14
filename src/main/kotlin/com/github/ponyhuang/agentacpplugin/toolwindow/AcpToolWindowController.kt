package com.github.ponyhuang.agentacpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.AcpAgentService
import com.github.ponyhuang.agentacpplugin.services.AcpProjectService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AcpToolWindowController(
    private val projectService: AcpProjectService,
    initialAgent: BuiltInAcpAgentRegistry.AgentDefinition = BuiltInAcpAgentRegistry.defaultAgent(),
    private val appendItem: (ToolWindowConversationItem) -> Unit,
    private val updateItem: (String, ToolWindowConversationItem) -> Unit,
    private val setComposerState: (ToolWindowComposerState) -> Unit,
    private val uiExecutor: (((() -> Unit))) -> Unit = { action ->
        ApplicationManager.getApplication().invokeLater(action)
    },
) : Disposable {

    private val logger = Logger.getInstance(AcpToolWindowController::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("AcpToolWindowController"))
    private val subscribedAgents = mutableSetOf<String>()
    private val itemStore = ConcurrentHashMap<String, ToolWindowConversationItem>()

    @Volatile
    private var selectedAgent = initialAgent

    @Volatile
    private var composerState = ToolWindowComposerState.IDLE

    @Volatile
    private var currentAssistantItemId: String? = null

    @Volatile
    private var currentThinkingItemId: String? = null

    fun selectedAgent(): BuiltInAcpAgentRegistry.AgentDefinition = selectedAgent

    fun selectAgent(agent: BuiltInAcpAgentRegistry.AgentDefinition) {
        if (composerState == ToolWindowComposerState.SENDING) {
            return
        }
        selectedAgent = agent
        emitItem(
            ToolWindowConversationItem.SystemStatus(
                itemId = nextItemId("agent"),
                text = "Switched to ${agent.displayName}",
            )
        )
    }

    fun submitPrompt(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || composerState != ToolWindowComposerState.IDLE) {
            return
        }
        scope.launch {
            runPrompt(prompt)
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private suspend fun runPrompt(prompt: String) {
        emitItem(ToolWindowConversationItem.UserText(nextItemId("user"), prompt))
        currentAssistantItemId = null
        currentThinkingItemId = null

        try {
            val descriptor = selectedAgent.toDescriptor()
            val service = projectService.getOrCreateAgentService(descriptor)
            subscribeToService(service)
            if (!service.isConnected) {
                updateComposerState(ToolWindowComposerState.CONNECTING)
                service.connect()
            }

            updateComposerState(ToolWindowComposerState.SENDING)
            service.sendPrompt(prompt).collect { event ->
                // Handle completion via the return value
            }
            updateComposerState(ToolWindowComposerState.IDLE)
            currentThinkingItemId = null
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            logger.warn("ACP prompt failed", t)
            emitItem(
                ToolWindowConversationItem.Error(
                    itemId = nextItemId("prompt-error"),
                    text = t.message ?: "Prompt failed",
                )
            )
            updateComposerState(ToolWindowComposerState.IDLE)
        }
    }

    private fun subscribeToService(service: AcpAgentService) {
        if (!subscribedAgents.add(service.descriptor.id)) {
            return
        }

        scope.launch {
            service.sessionUpdates.collectLatest { update ->
                handleSessionUpdate(update)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun handleSessionUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.UserMessageChunk -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("user-echo"),
                        text = "User echo: ${renderContentBlock(update.content)}",
                    )
                )
            }
            is SessionUpdate.AgentMessageChunk -> appendAssistantText(renderContentBlock(update.content))
            is SessionUpdate.AgentThoughtChunk -> appendThinkingText(renderContentBlock(update.content))
            is SessionUpdate.ToolCall -> {
                val item = ToolWindowConversationItem.ToolCall(
                    itemId = update.toolCallId.value,
                    title = update.title,
                    status = update.status?.toString(),
                    details = update.content.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.toString() },
                )
                emitOrUpdate(item)
            }
            is SessionUpdate.ToolCallUpdate -> {
                val item = ToolWindowConversationItem.ToolCall(
                    itemId = update.toolCallId.value,
                    title = update.title ?: "Tool update",
                    status = update.status?.toString(),
                    details = update.content?.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.toString() },
                )
                emitOrUpdate(item)
            }
            is SessionUpdate.PlanUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("plan"),
                        text = "Plan updated (${update.entries.size} steps)",
                    )
                )
            }
            is SessionUpdate.AvailableCommandsUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("commands"),
                        text = "Available commands: ${update.availableCommands.joinToString { it.name }}",
                    )
                )
            }
            is SessionUpdate.CurrentModeUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("mode"),
                        text = "Current mode: ${update.currentModeId.value}",
                    )
                )
            }
            is SessionUpdate.ConfigOptionUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("config"),
                        text = "Config updated (${update.configOptions.size} options)",
                    )
                )
            }
            is SessionUpdate.SessionInfoUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("session-info"),
                        text = buildString {
                            append("Session info updated")
                            update.title?.let { append(": $it") }
                        },
                    )
                )
            }
            is SessionUpdate.UsageUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("usage"),
                        text = "Usage ${update.used}/${update.size}",
                    )
                )
            }
            is SessionUpdate.UnknownSessionUpdate -> {
                emitItem(
                    ToolWindowConversationItem.SystemStatus(
                        itemId = nextItemId("unknown-update"),
                        text = "Unknown update type: ${update.sessionUpdateType}",
                    )
                )
            }
        }
    }

    private fun appendAssistantText(chunk: String) {
        val currentId = currentAssistantItemId
        if (currentId == null) {
            val itemId = nextItemId("assistant")
            currentAssistantItemId = itemId
            emitItem(ToolWindowConversationItem.AssistantText(itemId, chunk))
            return
        }

        emitUpdate(currentId) { existing ->
            val previous = (existing as? ToolWindowConversationItem.AssistantText)?.text.orEmpty()
            ToolWindowConversationItem.AssistantText(currentId, previous + chunk)
        }
    }

    private fun appendThinkingText(chunk: String) {
        val currentId = currentThinkingItemId
        if (currentId == null) {
            val itemId = nextItemId("thinking")
            currentThinkingItemId = itemId
            emitItem(ToolWindowConversationItem.Thinking(itemId, chunk))
            return
        }

        emitUpdate(currentId) { existing ->
            val previous = (existing as? ToolWindowConversationItem.Thinking)?.text.orEmpty()
            ToolWindowConversationItem.Thinking(currentId, previous + chunk)
        }
    }

    private fun renderContentBlock(content: ContentBlock): String {
        return when (content) {
            is ContentBlock.Text -> content.text
            else -> content.toString()
        }
    }

    private fun emitOrUpdate(item: ToolWindowConversationItem.ToolCall) {
        emitUpdate(item.itemId) { item }
    }

    private fun emitItem(item: ToolWindowConversationItem) {
        itemStore[item.itemId] = item
        uiExecutor {
            appendItem(item)
        }
    }

    private fun emitUpdate(
        itemId: String,
        transform: (ToolWindowConversationItem?) -> ToolWindowConversationItem,
    ) {
        val existing = itemStore[itemId]
        val updated = transform(existing)
        itemStore[itemId] = updated
        uiExecutor {
            updateItem(itemId, updated)
        }
    }

    private fun updateComposerState(newState: ToolWindowComposerState) {
        composerState = newState
        uiExecutor {
            setComposerState(newState)
        }
    }

    private fun nextItemId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
