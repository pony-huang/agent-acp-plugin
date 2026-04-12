package com.github.ponyhuang.agentacpplugin.services.acp

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class IdeClientSessionOperationsTest {
    @Test
    fun testPermissionRequestPrefersAllowOnce() = runBlocking {
        val handler = PermissionRequestHandler()
        val operations = IdeClientSessionOperations(
            fileSystemAdapter = FileSystemExtensionAdapter(Paths.get(".")),
            terminalAdapter = TerminalExtensionAdapter(Paths.get(".")),
            permissionRequestHandler = handler,
            sessionNotificationHandler = {},
        )
        val response = operations.requestPermissions(
            toolCall = SessionUpdate.ToolCallUpdate(
                toolCallId = com.agentclientprotocol.model.ToolCallId("tool-1"),
                title = "terminal",
            ),
            permissions = listOf(
                PermissionOption(PermissionOptionId("reject"), "Reject", PermissionOptionKind.REJECT_ONCE),
                PermissionOption(PermissionOptionId("allow"), "Allow once", PermissionOptionKind.ALLOW_ONCE),
            ),
        )
        val outcome = response.outcome as RequestPermissionOutcome.Selected
        assertEquals("allow", outcome.optionId.toString())
    }

    @Test
    fun testNotifyForwardsSessionUpdate() = runBlocking {
        var seen = false
        val operations = IdeClientSessionOperations(
            fileSystemAdapter = FileSystemExtensionAdapter(Paths.get(".")),
            terminalAdapter = TerminalExtensionAdapter(Paths.get(".")),
            permissionRequestHandler = PermissionRequestHandler(),
        ) {
            seen = it is SessionUpdate.CurrentModeUpdate
        }
        operations.notify(SessionUpdate.CurrentModeUpdate(com.agentclientprotocol.model.SessionModeId("review")))
        assertTrue(seen)
    }
}
