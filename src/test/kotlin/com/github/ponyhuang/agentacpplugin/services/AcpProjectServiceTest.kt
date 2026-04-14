package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.common.Event
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class AcpProjectServiceTest : BasePlatformTestCase() {

    fun testServiceStartsIdle() {
        val service = AcpProjectService(project)
        try {
            assertEquals(AcpConnectionState.Idle, service.connectionState.value)
            assertFalse(service.isConnected)
        } finally {
            service.dispose()
        }
    }

    fun testConnectAndDisconnectUpdatesState() = runBlocking {
        val service = AcpProjectService(project)
        try {
            val connected = service.connect("npx.cmd", "@agentclientprotocol/claude-agent-acp")
            assertEquals(listOf("npx.cmd", "@agentclientprotocol/claude-agent-acp"), connected.command)
            print(connected.sessionId)
            assertEquals("test-session", connected.sessionId)
            assertTrue(service.isConnected)
            service.disconnect("test complete")
            val disconnected = service.connectionState.value as AcpConnectionState.Disconnected
            assertEquals("test complete", disconnected.reason)
        } finally {
            service.dispose()
        }
    }

    fun testSendPromptRequiresConnection() {
        val service = AcpProjectService(project)
        try {
            val error: IllegalStateException = try {
                runBlocking {
                    service.sendPrompt("hello").toList()
                }
                error("Expected sendPrompt to fail when ACP is disconnected")
            } catch (t: IllegalStateException) {
                t
            }

            assertEquals("ACP is not connected", error.message)
        } finally {
            service.dispose()
        }
    }

    fun testSendPromptUsesConnectedSession() = runBlocking {
        val service = AcpProjectService(project)
        try {
            service.connect("npx.cmd", "@agentclientprotocol/claude-agent-acp")
            val events = service.sendPrompt("hello ACP").toList()
            assertEquals(2, events.size)
            assertTrue(events[0] is Event.SessionUpdateEvent)
            assertTrue(events[1] is Event.PromptResponseEvent)
        } finally {
            service.dispose()
        }
    }

}
