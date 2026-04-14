package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.UUID

class TerminalClientSessionOperationsTest : BasePlatformTestCase() {

    fun testPermissionAutoApprovePrefersAllowOption() = runBlocking {
        val events = mutableListOf<AcpServiceEvent>()
        val operations = TerminalClientSessionOperations(
            project = project,
            coroutineScope = testScope,
            eventSink = { events += it },
        )

        val response = operations.requestPermissions(
            toolCall = SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId("tool-1"),
                title = "write file",
            ),
            permissions = listOf(
                PermissionOption(
                    optionId = PermissionOptionId("reject"),
                    name = "Reject once",
                    kind = PermissionOptionKind.REJECT_ONCE,
                ),
                PermissionOption(
                    optionId = PermissionOptionId("allow"),
                    name = "Allow once",
                    kind = PermissionOptionKind.ALLOW_ONCE,
                ),
            ),
            _meta = null,
        )

        val outcome = response.outcome as RequestPermissionOutcome.Selected
        assertEquals("allow", outcome.optionId.value)
        assertEquals(
            AcpServiceEvent.PermissionAutoApproved(
                toolCallTitle = "write file",
                option = PermissionOption(
                    optionId = PermissionOptionId("allow"),
                    name = "Allow once",
                    kind = PermissionOptionKind.ALLOW_ONCE,
                ),
            ),
            events.single(),
        )
    }

    fun testReadAndWriteUseProjectRelativePaths() = runBlocking {
        val operations = TerminalClientSessionOperations(
            project = project,
            coroutineScope = testScope,
            eventSink = {},
        )
        val relativePath = "build/test-${UUID.randomUUID()}.txt"

        operations.fsWriteTextFile(relativePath, "alpha${System.lineSeparator()}beta", null)
        val response = operations.fsReadTextFile(relativePath, 2u, 1u, null)

        assertEquals("beta", response.content)
    }

    private companion object {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }
}
