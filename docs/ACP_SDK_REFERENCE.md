# ACP SDK Reference

This document provides a comprehensive reference for the ACP (Agent Client Protocol) Kotlin SDK integration based on source code analysis of `E:\workplace\kotlin-sdk`.

## Table of Contents

1. [Module Structure](#module-structure)
2. [Core Types](#core-types)
3. [Client API](#client-api)
4. [ClientSession API](#clientsession-api)
5. [SessionUpdate Sealed Class](#sessionupdate-sealed-class)
6. [ContentBlock Sealed Class](#contentblock-sealed-class)
7. [ClientSessionOperations Interface](#clientsessionoperations-interface)
8. [Request/Response Types](#requestresponse-types)
9. [Transport Layer](#transport-layer)
10. [Permission Flow](#permission-flow)
11. [Project Integration](#project-integration)

---

## Module Structure

```
com.azure.agentchat
├── Client.kt              # Main client entry point
├── ClientSession.kt       # Session operations
├── ClientSessionOperations.kt  # Permission/terminal callbacks
├── ContentBlock.kt        # Message content types
├── SessionUpdate.kt       # Streaming event types
├── Transport.kt           # Transport layer interface
├── StdioTransport.kt      # Stdio implementation
├── types/                 # Core type definitions
│   ├── SessionId.kt
│   ├── ModelId.kt
│   ├── SessionModeId.kt
│   ├── ToolCallId.kt
│   └── ...
```

---

## Core Types

### SessionId
Represents a unique session identifier.

```kotlin
@JvmInline
value class SessionId(val value: String)
```

### ModelId
Represents a model identifier.

```kotlin
@JvmInline
value class ModelId(val value: String)
```

### SessionModeId
Represents a session mode identifier.

```kotlin
@JvmInline
value class SessionModeId(val value: String)
```

### ToolCallId
Represents a tool call identifier.

```kotlin
@JvmInline
value class ToolCallId(val value: String)
```

---

## Client API

### Initialize Client

```kotlin
val transport = StdioTransport()
val client = Client(transport)
```

### Create New Session

```kotlin
@OptIn(UnstableApi::class)
suspend fun newSession(
    instructions: String? = null,
    sessionId: SessionId? = null
): ClientSession
```

**Parameters:**
- `instructions`: Optional system instructions
- `sessionId`: Optional session ID (auto-generated if null)

**Returns:** `ClientSession` instance

### Load Existing Session

```kotlin
@OptIn(UnstableApi::class)
suspend fun loadSession(sessionId: SessionId): ClientSession
```

**Parameters:**
- `sessionId`: The session ID to load

**Returns:** `ClientSession` instance

**Note:** This method does NOT auto-create a session if the session doesn't exist. It throws an error.

---

## ClientSession API

### Send Prompt

```kotlin
@OptIn(UnstableApi::class)
suspend fun prompt(
    input: String,
    images: List<ByteArray> = emptyList(),
    audio: List<ByteArray> = emptyList(),
    contextId: String? = null
): SessionUpdateFlow
```

**Parameters:**
- `input`: The user input text
- `images`: Optional list of image bytes
- `audio`: Optional list of audio bytes
- `contextId`: Optional conversation context ID

**Returns:** `SessionUpdateFlow` for receiving streaming updates

### Cancel Operation

```kotlin
@OptIn(UnstableApi::class)
suspend fun cancel()
```

Cancels the current in-progress operation.

### Set Mode

```kotlin
@OptIn(UnstableApi::class)
suspend fun setMode(modeId: SessionModeId)
```

**Parameters:**
- `modeId`: The mode ID to switch to (e.g., `SessionModeId("Code")`)

### Set Model

```kotlin
@OptIn(UnstableApi::class)
suspend fun setModel(modelId: ModelId)
```

**Parameters:**
- `modelId`: The model ID to switch to (e.g., `ModelId("gpt-4")`)

### Close Session

```kotlin
fun close()
```

---

## SessionUpdate Sealed Class

The `SessionUpdate` sealed class represents all possible streaming events from the agent. Here's the complete hierarchy:

### SessionUpdate Types

```kotlin
sealed class SessionUpdate {
    // Text chunks
    class UserMessageChunk(val content: String) : SessionUpdate()
    class AgentMessageChunk(val content: String) : SessionUpdate()
    class AgentThoughtChunk(val content: String) : SessionUpdate()

    // Tool operations
    class ToolCall(
        val toolCallId: ToolCallId,
        val toolName: String,
        val input: JsonElement
    ) : SessionUpdate()

    class ToolCallUpdate(
        val toolCallId: ToolCallId,
        val status: ToolCallStatus,
        val output: JsonElement?,
        val availableVars: Map<String, JsonElement>?
    ) : SessionUpdate()

    // Plan operations
    class PlanUpdate(
        val entries: List<PlanEntry>
    ) : SessionUpdate()

    class PlanEntry(
        val status: PlanStatus,
        val content: String,
        val notes: List<String>
    )

    enum class PlanStatus { Pending, InProgress, Completed, Skipped, Failed }

    // State updates
    class AvailableCommandsUpdate(
        val commands: List<Command>
    ) : SessionUpdate()

    class CurrentModeUpdate(
        val modeId: SessionModeId
    ) : SessionUpdate()

    class UsageUpdate(
        val totalUsage: Usage
    ) : SessionUpdate()

    class Usage(
        val totalUsage: Long,
        val promptTokens: Long,
        val completionTokens: Long
    )

    // Configuration
    class ConfigOptionUpdate(
        val options: List<ConfigOption>
    ) : SessionUpdate()

    class ConfigOption(
        val optionId: String,
        val description: String,
        val valueType: ConfigValueType,
        val required: Boolean,
        val currentValue: JsonElement?
    )

    enum class ConfigValueType { String, Number, Boolean, Select }

    // Session info
    class SessionInfoUpdate(
        val sessionId: SessionId,
        val instructions: String?,
        val authStatus: AuthStatus
    ) : SessionUpdate()

    // Unknown/extended types
    class UnknownSessionUpdate(
        val type: String,
        val payload: JsonElement
    ) : SessionUpdate()
}
```

### ToolCallStatus Enum

```kotlin
enum class ToolCallStatus {
    Pending,
    InProgress,
    Completed,
    Error,
    Denied,
    Cancelled
}
```

### AuthStatus Enum

```kotlin
enum class AuthStatus {
    Authenticated,
    Unauthenticated,
    Partial
}
```

---

## ContentBlock Sealed Class

The `ContentBlock` class represents different types of content within a message. Here's the complete hierarchy:

```kotlin
sealed class ContentBlock {
    class Text(val text: String) : ContentBlock()
    class Image(val data: ByteArray, val mediaType: String) : ContentBlock()
    class Audio(val data: ByteArray, val mediaType: String) : ContentBlock()
    class ResourceLink(val uri: String) : ContentBlock()
    class Resource(val resource: ResourceInfo) : ContentBlock()
}

class ResourceInfo(
    val name: String,
    val description: String?,
    val mimeType: String?
)
```

**Note:** The Kotlin SDK does NOT have `ToolUse` or `ToolResult` content block types.

---

## ClientSessionOperations Interface

This interface defines callbacks for session operations that require user interaction or system integration.

```kotlin
interface ClientSessionOperations {
    val authToken: suspend () -> String?
    val permissionRequest: suspend (
        toolCallUpdate: SessionUpdate.ToolCallUpdate,
        permissions: List<PermissionOption>,
        context: JsonElement?
    ) -> RequestPermissionResponse

    val getConfirmation: suspend (confirmationRequest: ConfirmationRequest): ConfirmationResult
    val selectOption: suspend (selectRequest: SelectOptionRequest): SelectOptionResult
    val getWorkspaceUri: suspend () -> String?
    val getInput: suspend (inputRequest: InputRequest): InputResult
}

data class PermissionOption(
    val optionId: String,
    val label: String,
    val description: String?,
    val requiresConfirmation: Boolean
)

sealed class RequestPermissionResponse {
    class Selected(val optionId: String, val confirmed: Boolean?) : RequestPermissionResponse()
    data object Cancelled : RequestPermissionResponse()
    data object Denied : RequestPermissionResponse()
}

data class ConfirmationRequest(
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String
)

data class SelectOptionRequest(
    val message: String,
    val options: List<SelectOption>
)

data class SelectOption(
    val optionId: String,
    val label: String,
    val description: String?
)

data class InputRequest(
    val message: String,
    val defaultValue: String?
)
```

### Default Implementation

```kotlin
class DefaultClientSessionOperations(
    val sessionUpdateSink: suspend (SessionUpdate) -> Unit,
    val permissionRequestSink: suspend (
        SessionUpdate.ToolCallUpdate,
        List<PermissionOption>,
        JsonElement?
    ) -> RequestPermissionResponse = { toolCall, permissions, _ ->
        // Default: Auto-grant first permission or cancel
        if (permissions.isNotEmpty()) {
            RequestPermissionResponse(RequestPermissionOutcome.Selected(permissions[0].optionId), null)
        } else {
            RequestPermissionResponse(RequestPermissionOutcome.Cancelled, null)
        }
    },
) : ClientSessionOperations {
    override val authToken: suspend () -> String? = { null }
    override val getConfirmation: suspend (ConfirmationRequest) -> ConfirmationResult = { ConfirmationResult(false) }
    override val selectOption: suspend (SelectOptionRequest) -> SelectOptionResult = { SelectOptionResult(null) }
    override val getWorkspaceUri: suspend () -> String? = { null }
    override val getInput: suspend (InputRequest) -> InputResult = { InputResult(null) }
}
```

---

## Request/Response Types

### SessionUpdateFlow

A `SessionUpdateFlow` is returned from `prompt()` and emits `SessionUpdate` events. It's essentially a `Flow<SessionUpdate>` with additional session management methods.

```kotlin
interface SessionUpdateFlow {
    val sessionId: SessionId
    val sessionUpdates: Flow<SessionUpdate>

    // Terminal operations
    suspend fun waitForComplete()
    suspend fun cancel()
}
```

### ConfirmationResult

```kotlin
data class ConfirmationResult(
    val confirmed: Boolean
)
```

### SelectOptionResult

```kotlin
data class SelectOptionResult(
    val selectedOptionId: String?
)
```

### InputResult

```kotlin
data class InputResult(
    val input: String?
)
```

---

## Transport Layer

### Transport Interface

```kotlin
interface Transport {
    val sessionState: StateFlow<TransportSessionState>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(message: JsonRpcMessage)
    fun receive(): Flow<JsonRpcMessage>
}
```

### StdioTransport

The `StdioTransport` uses standard input/output for communication with the agent subprocess.

```kotlin
class StdioTransport(
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val processReuse: Boolean = true,
    val startupTimeoutMs: Long = 30_000
) : Transport
```

**Note:** When using `StdioTransport`, ensure proper process lifecycle management and timeout handling.

---

## Permission Flow

When an agent requests permissions:

1. Agent sends `SessionUpdate.ToolCall` with tool call details
2. SDK calls `permissionRequest` callback with available `PermissionOption` list
3. User/agent selects an option via `RequestPermissionResponse.Selected`
4. Result is sent back to agent via the tool call response

### Permission Option Example

```kotlin
val permissionOptions = listOf(
    PermissionOption(
        optionId = "allow",
        label = "Allow",
        description = "Allow this operation",
        requiresConfirmation = false
    ),
    PermissionOption(
        optionId = "deny",
        label = "Deny",
        description = "Deny this operation",
        requiresConfirmation = false
    )
)

val response = clientSessionOperations.permissionRequest(
    toolCallUpdate,
    permissionOptions,
    null  // context
)
```

---

## Project Integration

### Basic Integration Pattern

```kotlin
class AcpSessionService {
    private val transport = StdioTransport()
    private val client = Client(transport)

    private val sessionOperations = DefaultClientSessionOperations(
        sessionUpdateSink = { update ->
            // Handle SessionUpdate events
            handleSessionUpdate(update)
        }
    )

    private var _currentSession: ClientSession? = null

    @OptIn(UnstableApi::class)
    suspend fun createSession(): ClientSession {
        val session = client.newSession(
            instructions = "Your system instructions here",
            sessionId = SessionId("optional-session-id")
        )
        _currentSession = session
        return session
    }

    @OptIn(UnstableApi::class)
    suspend fun sendMessage(input: String) {
        val session = _currentSession ?: createSession()
        val updateFlow = session.prompt(input)

        updateFlow.sessionUpdates.collect { update ->
            handleSessionUpdate(update)
        }
    }

    private suspend fun handleSessionUpdate(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.UserMessageChunk -> { /* Handle user message */ }
            is SessionUpdate.AgentMessageChunk -> { /* Handle agent message */ }
            is SessionUpdate.AgentThoughtChunk -> { /* Handle agent thought */ }
            is SessionUpdate.ToolCall -> { /* Handle tool call */ }
            is SessionUpdate.ToolCallUpdate -> { /* Handle tool result */ }
            is SessionUpdate.PlanUpdate -> { /* Handle plan update */ }
            is SessionUpdate.AvailableCommandsUpdate -> { /* Handle commands */ }
            is SessionUpdate.CurrentModeUpdate -> { /* Handle mode change */ }
            is SessionUpdate.UsageUpdate -> { /* Handle usage stats */ }
            is SessionUpdate.ConfigOptionUpdate -> { /* Handle config options */ }
            is SessionUpdate.SessionInfoUpdate -> { /* Handle session info */ }
            is SessionUpdate.UnknownSessionUpdate -> { /* Handle unknown type */ }
        }
    }
}
```

### SessionUpdate Handling Pattern

For handling streaming updates, use `onEach` or `collect` on the `sessionUpdates` flow:

```kotlin
suspend fun sendPrompt(input: String) {
    val flow = session.prompt(input)

    flow.sessionUpdates
        .onEach { update -> handleSessionUpdate(update) }
        .collect()
}
```

### Cancellation Support

The SDK supports cancellation via `cancel()`:

```kotlin
@OptIn(UnstableApi::class)
suspend fun cancel() {
    _currentSession?.cancel()
}
```

### Mode/Model Switching

```kotlin
@OptIn(UnstableApi::class)
suspend fun setMode(modeId: String) {
    _currentSession?.setMode(SessionModeId(modeId))
}

@OptIn(UnstableApi::class)
suspend fun setModel(modelId: String) {
    _currentSession?.setModel(ModelId(modelId))
}
```

---

## Common Patterns

### Pattern: Iterate Session Updates

```kotlin
suspend fun processSession(session: ClientSession, input: String) {
    val flow = session.prompt(input)

    for (update in flow.sessionUpdates) {
        when (update) {
            is SessionUpdate.AgentMessageChunk -> println("Agent: ${update.content}")
            is SessionUpdate.ToolCall -> println("Tool call: ${update.toolName}")
            else -> { /* Handle other updates */ }
        }
    }
}
```

### Pattern: Wait for Completion

```kotlin
suspend fun waitForResult(session: ClientSession, input: String): String {
    val flow = session.prompt(input)
    var result = ""

    flow.sessionUpdates.collect { update ->
        when (update) {
            is SessionUpdate.AgentMessageChunk -> result += update.content
            is SessionUpdate.SessionInfoUpdate -> { /* Session info received */ }
            else -> { /* Other updates */ }
        }
    }

    return result
}
```

### Pattern: With Coroutine Cancellation

```kotlin
suspend fun cancellablePrompt(input: String) = withContext(Dispatchers.IO) {
    val flow = _currentSession?.prompt(input) ?: return@withContext

    try {
        flow.sessionUpdates.collect { update ->
            // Process update
        }
    } catch (e: CancellationException) {
        _currentSession?.cancel()
        throw e
    }
}
```

---

## Error Handling

### Transport Errors

```kotlin
try {
    transport.connect()
} catch (e: TransportException) {
    // Handle connection error
}
```

### Session Errors

```kotlin
try {
    val flow = session.prompt(input)
    flow.sessionUpdates.collect { /* ... */ }
} catch (e: SessionException) {
    // Handle session error
}
```

---

## Best Practices

1. **Always close sessions** when done: `session.close()`
2. **Handle cancellation properly**: Use try-finally or use `withContext` for cancellation
3. **Use `@OptIn(UnstableApi::class)`**: Many APIs are marked as unstable
4. **Subscribe to `sessionUpdateSink` early**: To not miss any updates
5. **Handle `UnknownSessionUpdate`**: For forward compatibility with new update types
6. **Set appropriate timeouts**: For `StdioTransport` startup timeout
7. **Process lifecycle**: Call `transport.disconnect()` when shutting down

---

## References

- Kotlin SDK Location: `E:\workplace\kotlin-sdk`
- Original TypeScript Project: `E:\workplace\acp-ui`
- IntelliJ Plugin: `E:\workplace\agent-acp-plugin`
