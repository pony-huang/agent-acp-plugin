package com.github.ponyhuang.agentacpplugin.services

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.SetSessionConfigOptionResponse
import com.agentclientprotocol.model.SetSessionModeResponse
import com.agentclientprotocol.model.SetSessionModelResponse
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

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
            val connector = FakeRuntimeConnector(project)
            projectService.replaceRuntimeConnectorForTests(connector)
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
            assertEquals(1, connector.closeCalls)
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
        val projectService = AcpProjectService(project)
        try {
            val connector = FakeRuntimeConnector(project)
            projectService.replaceRuntimeConnectorForTests(connector)
            val descriptor = AcpAgentDescriptor(
                id = "claude",
                displayName = "Claude Code",
                command = "npx",
                args = listOf("@agentclientprotocol/claude-agent-acp"),
            )
            val agentService = projectService.getOrCreateAgentService(descriptor)
            agentService.connect()

            val events = agentService.sendPrompt("hello ACP").toList()

            assertEquals(2, events.size)
            assertEquals("hello ACP", connector.session.lastPromptText)
            assertTrue(events[0] is Event.SessionUpdateEvent)
            assertTrue(events[1] is Event.PromptResponseEvent)
        } finally {
            projectService.dispose()
        }
    }

    private class FakeRuntimeConnector(project: com.intellij.openapi.project.Project) : AcpRuntimeConnector {
        var closeCalls = 0
        val session = FakeClientSession(project)

        override suspend fun connect(
            project: com.intellij.openapi.project.Project,
            scope: CoroutineScope,
            descriptor: AcpAgentDescriptor,
            eventSink: suspend (AcpServiceEvent) -> Unit,
        ): AcpRuntimeConnection {
            return object : AcpRuntimeConnection {
                override val descriptor: AcpAgentDescriptor = descriptor
                override val client: Client = this@FakeRuntimeConnector.session.client
                override val session: ClientSession = this@FakeRuntimeConnector.session
                override val agentInfo: AgentInfo = AgentInfo()

                override suspend fun close() {
                    closeCalls += 1
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private class FakeClientSession(project: com.intellij.openapi.project.Project) : ClientSession {
        private val emptyModes = MutableStateFlow(SessionModeId("default"))
        private val emptyModels = MutableStateFlow(ModelId("default"))
        private val emptyConfig = MutableStateFlow<List<SessionConfigOption>>(emptyList())

        override val client: Client = Client(Protocol(testScope, DummyTransport()))
        var lastPromptText: String? = null

        override val sessionId: SessionId = SessionId("test-session")
        override val parameters: SessionCreationParameters =
            SessionCreationParameters(project.basePath ?: ".", emptyList())
        override val operations: ClientSessionOperations = object : ClientSessionOperations {
            override suspend fun requestPermissions(
                toolCall: SessionUpdate.ToolCallUpdate,
                permissions: List<PermissionOption>,
                _meta: JsonElement?,
            ): RequestPermissionResponse {
                error("Not used in tests")
            }

            override suspend fun notify(notification: SessionUpdate, _meta: JsonElement?) = Unit

            override suspend fun fsReadTextFile(path: String, line: UInt?, limit: UInt?, _meta: JsonElement?) =
                error("Not used in tests")

            override suspend fun fsWriteTextFile(path: String, content: String, _meta: JsonElement?) =
                error("Not used in tests")

            override suspend fun terminalCreate(
                command: String,
                args: List<String>,
                cwd: String?,
                env: List<com.agentclientprotocol.model.EnvVariable>,
                outputByteLimit: ULong?,
                _meta: JsonElement?,
            ) = error("Not used in tests")

            override suspend fun terminalOutput(terminalId: String, _meta: JsonElement?) =
                error("Not used in tests")

            override suspend fun terminalRelease(terminalId: String, _meta: JsonElement?) =
                error("Not used in tests")

            override suspend fun terminalWaitForExit(terminalId: String, _meta: JsonElement?) =
                error("Not used in tests")

            override suspend fun terminalKill(terminalId: String, _meta: JsonElement?) =
                error("Not used in tests")
        }

        override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
            lastPromptText = (content.single() as ContentBlock.Text).text
            emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text("pong"))))
            emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
        }

        override suspend fun cancel() = Unit

        override suspend fun close(_meta: JsonElement?) = com.agentclientprotocol.model.CloseSessionResponse()

        override val modesSupported: Boolean = false
        override val availableModes: List<SessionMode> = emptyList()
        override val currentMode: StateFlow<SessionModeId> = emptyModes

        override suspend fun setMode(modeId: SessionModeId, _meta: JsonElement?) = SetSessionModeResponse()

        override val modelsSupported: Boolean = false
        override val availableModels: List<com.agentclientprotocol.model.ModelInfo> = emptyList()
        override val currentModel: StateFlow<ModelId> = emptyModels

        override suspend fun setModel(modelId: ModelId, _meta: JsonElement?) = SetSessionModelResponse()

        override val configOptionsSupported: Boolean = false
        override val configOptions: StateFlow<List<SessionConfigOption>> = emptyConfig

        override suspend fun setConfigOption(
            configId: SessionConfigId,
            value: SessionConfigOptionValue,
            _meta: JsonElement?,
        ) = SetSessionConfigOptionResponse(emptyList())
    }

    private class DummyTransport : BaseTransport() {
        override fun start() {
            _state.value = Transport.State.STARTED
        }

        override fun send(message: JsonRpcMessage) = Unit

        override fun close() {
            _state.value = Transport.State.CLOSED
        }
    }

    private companion object {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }
}
