package com.github.ponyhuang.agentacpplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class AcpProjectServiceTest : BasePlatformTestCase() {

    fun testGetOrCreateAgentServiceReusesSameDescriptorId() = runBlocking {
        val projectService = AcpProjectService(project)
        try {
            val descriptor = AcpAgentDescriptor(
                id = "claude",
                displayName = "Claude Code",
                command = "npx",
                args = listOf("@agentclientprotocol/claude-agent-acp"),
            )

            val first = projectService.getOrCreateAgentService(descriptor)
            val second = projectService.getOrCreateAgentService(descriptor.copy(displayName = "Claude"))

            assertSame(first, second)
            assertEquals(listOf(first), projectService.listAgentServices())
        } finally {
            projectService.dispose()
        }
    }

    fun testGetOrCreateAgentServiceRejectsDifferentConfigForSameId() = runBlocking {
        val projectService = AcpProjectService(project)
        try {
            projectService.getOrCreateAgentService(
                AcpAgentDescriptor(
                    id = "claude",
                    displayName = "Claude Code",
                    command = "npx",
                    args = listOf("@agentclientprotocol/claude-agent-acp"),
                )
            )

            val error = try {
                projectService.getOrCreateAgentService(
                    AcpAgentDescriptor(
                        id = "claude",
                        displayName = "Claude Code",
                        command = "npx",
                        args = listOf("@zed-industries/claude-code-acp@latest"),
                    )
                )
                error("Expected conflicting agent config to fail")
            } catch (t: IllegalArgumentException) {
                t
            }

            assertEquals(
                "ACP agent 'claude' is already registered with a different configuration. Remove it before recreating.",
                error.message,
            )
        } finally {
            projectService.dispose()
        }
    }

    fun testConnectCreatesAgentRuntimeAndDisconnectsIt() = runBlocking {
        val projectService = AcpProjectService(project)
        try {


            val descriptor = AcpAgentDescriptor(
                id = "claude",
                displayName = "Claude Code",
                command = "npx",
                args = listOf("@agentclientprotocol/claude-agent-acp"),
            )

            val connected = projectService.connect(descriptor)
            val agentService = projectService.getAgentService("claude") ?: error("missing agent service")

            assertEquals(descriptor, connected.descriptor)
            assertTrue(agentService.isConnected)

            projectService.removeAgentService("claude", "removed in test")
            assertNull(projectService.getAgentService("claude"))
        } finally {
            projectService.dispose()
        }
    }

    fun testPerAgentServiceSendPromptRequiresConnection() {
        val projectService = AcpProjectService(project)
        try {
            val descriptor = AcpAgentDescriptor(
                id = "claude",
                displayName = "Claude Code",
                command = "npx",
                args = listOf("@agentclientprotocol/claude-agent-acp"),
            )
            val agentService = runBlocking { projectService.getOrCreateAgentService(descriptor) }

            val error = try {
                runBlocking {
                    agentService.sendPrompt("hello").toList()
                }
                error("Expected sendPrompt to fail when ACP is disconnected")
            } catch (t: IllegalStateException) {
                t
            }

            assertEquals("ACP agent Claude Code is not connected", error.message)
        } finally {
            projectService.dispose()
        }
    }

    fun testPerAgentServiceSendPromptUsesConnectedSession() = runBlocking {
        myFixture.addFileToProject("README.md", "seed")
        val projectService = AcpProjectService(project)
        try {
            val descriptor = AcpAgentDescriptor(
                id = "claude",
                displayName = "Claude Code",
                command = "npx.cmd",
                args = listOf("@agentclientprotocol/claude-agent-acp"),
            )
            val agentService = projectService.getOrCreateAgentService(descriptor)
            agentService.connect()
            val events =
                agentService.sendPrompt("Create a new file called 'hello.txt' with the content 'Hello, World!'")
                    .toList()
            for (event in events) {
                print(event)
            }
        } finally {
            projectService.dispose()
        }
    }


}
