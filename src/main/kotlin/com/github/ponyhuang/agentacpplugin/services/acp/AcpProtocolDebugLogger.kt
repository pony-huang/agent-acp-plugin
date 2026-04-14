package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.github.ponyhuang.agentacpplugin.services.session.TurnCompletionReason
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path

internal object AcpProtocolDebugLogger {
    private const val MAX_PREVIEW_LENGTH = 160

    fun logConnectRequested(
        logger: Logger,
        endpointId: String,
        endpointName: String,
        commandLine: String,
        workspaceRoot: Path,
    ) {
        logger.info(
            "ACP connect requested endpointId=$endpointId endpointName=$endpointName " +
                "commandLine=\"${previewValue(commandLine)}\" workspaceRoot=$workspaceRoot",
        )
    }

    fun logConnectSucceeded(logger: Logger, endpointId: String, endpointName: String, sessionId: String) {
        logger.info("ACP connect succeeded endpointId=$endpointId endpointName=$endpointName sessionId=$sessionId")
    }

    fun logConnectFailed(logger: Logger, endpointId: String, endpointName: String, error: Throwable) {
        logger.warn(
            "ACP connect failed endpointId=$endpointId endpointName=$endpointName message=\"${previewValue(error.message)}\"",
            error,
        )
    }

    fun logPromptSubmitted(logger: Logger, sessionId: String, prompt: String) {
        logger.info("ACP prompt submitted sessionId=$sessionId prompt=\"${previewValue(prompt)}\"")
    }

    fun logPromptCancellationRequested(logger: Logger, sessionId: String) {
        logger.info("ACP prompt cancellation requested sessionId=$sessionId")
    }

    fun logSessionDisconnected(logger: Logger, sessionId: String) {
        logger.info("ACP session disconnected sessionId=$sessionId")
    }

    @OptIn(UnstableApi::class)
    fun logSessionUpdate(logger: Logger, source: String, sessionId: String, update: SessionUpdate) {
        logger.info(
            "ACP session update source=$source sessionId=$sessionId type=${update.javaClass.simpleName} " +
                "summary=${sessionUpdateSummary(update)} payload=$update",
        )
    }

    fun logPromptFinished(logger: Logger, source: String, sessionId: String, reason: TurnCompletionReason) {
        logger.info("ACP prompt finished source=$source sessionId=$sessionId reason=$reason")
    }

    fun logPromptFailed(logger: Logger, source: String, sessionId: String, message: String) {
        logger.warn("ACP prompt failed source=$source sessionId=$sessionId message=\"${previewValue(message)}\"")
    }

    fun logPermissionRequest(
        logger: Logger,
        sessionId: String,
        toolCall: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
    ) {
        logger.info(
            "ACP permission request sessionId=$sessionId toolCallId=${toolCall.toolCallId} " +
                "title=\"${previewValue(toolCall.title ?: "Tool")}\" " +
                "options=${permissions.joinToString(prefix = "[", postfix = "]") { "${it.kind}:${previewValue(it.name)}" }}",
        )
    }

    fun logPermissionSelection(logger: Logger, toolTitle: String, optionName: String, optionKind: String) {
        logger.info(
            "ACP permission selected toolTitle=\"${previewValue(toolTitle)}\" " +
                "option=\"${previewValue(optionName)}\" kind=$optionKind",
        )
    }

    @OptIn(UnstableApi::class)
    internal fun sessionUpdateSummary(update: SessionUpdate): String {
        return when (update) {
            is SessionUpdate.AgentMessageChunk -> {
                "messageId=${update.messageId} preview=\"${previewContentBlock(update.content)}\""
            }

            is SessionUpdate.AgentThoughtChunk -> {
                "messageId=${update.messageId} preview=\"${previewContentBlock(update.content)}\""
            }

            is SessionUpdate.UserMessageChunk -> {
                "messageId=${update.messageId} preview=\"${previewContentBlock(update.content)}\""
            }

            is SessionUpdate.ToolCall -> {
                "toolCallId=${update.toolCallId} title=\"${previewValue(update.title)}\" status=${update.status} " +
                    "kind=${update.kind} content=${toolCallContentSummary(update.content)}"
            }

            is SessionUpdate.ToolCallUpdate -> {
                "toolCallId=${update.toolCallId} title=\"${previewValue(update.title ?: "Tool")}\" status=${update.status} " +
                    "kind=${update.kind} content=${toolCallContentSummary(update.content.orEmpty())}"
            }

            is SessionUpdate.PlanUpdate -> {
                "entries=${update.entries.size} preview=\"${previewValue(update.entries.joinToString { it.content })}\""
            }

            is SessionUpdate.AvailableCommandsUpdate -> {
                "count=${update.availableCommands.size} names=${update.availableCommands.joinToString(prefix = "[", postfix = "]") { it.name }}"
            }

            is SessionUpdate.CurrentModeUpdate -> {
                "currentModeId=${update.currentModeId}"
            }

            is SessionUpdate.ConfigOptionUpdate -> {
                "count=${update.configOptions.size} options=${update.configOptions.joinToString(prefix = "[", postfix = "]") { previewValue(it.toString()) }}"
            }

            is SessionUpdate.SessionInfoUpdate -> {
                "title=\"${previewValue(update.title)}\" updatedAt=${update.updatedAt}"
            }

            is SessionUpdate.UsageUpdate -> {
                "used=${update.used} size=${update.size} cost=${update.cost?.let { "${it.amount} ${it.currency}" } ?: "null"}"
            }

            is SessionUpdate.UnknownSessionUpdate -> {
                "sessionUpdateType=${update.sessionUpdateType}"
            }
        }
    }

    internal fun previewValue(value: Any?): String {
        val normalized = (value?.toString() ?: "null")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (normalized.length <= MAX_PREVIEW_LENGTH) {
            normalized
        } else {
            normalized.take(MAX_PREVIEW_LENGTH - 3) + "..."
        }
    }

    private fun previewContentBlock(content: ContentBlock): String {
        val text = when (content) {
            is ContentBlock.Text -> content.text
            else -> content.toString()
        }
        return previewValue(text)
    }

    private fun toolCallContentSummary(content: List<ToolCallContent>): String {
        if (content.isEmpty()) {
            return "[]"
        }
        return content.joinToString(prefix = "[", postfix = "]") { item ->
            when (item) {
                is ToolCallContent.Content -> "content:${previewContentBlock(item.content)}"
                is ToolCallContent.Diff -> "diff:${previewValue(item.path)}"
                is ToolCallContent.Terminal -> "terminal:${previewValue(item.terminalId)}"
            }
        }
    }
}
