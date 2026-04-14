package com.github.ponyhuang.agentacpplugin.toolwindow

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.AcpAgentDescriptor
import com.github.ponyhuang.agentacpplugin.services.AcpAgentService
import com.github.ponyhuang.agentacpplugin.services.AcpProjectService
import com.github.ponyhuang.agentacpplugin.services.AcpRuntimeConnector
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

class AcpToolWindowControllerTest : BasePlatformTestCase() {

    fun testSubmitPromptAppendsUserAndAssistantItems() = runBlocking {
        val projectService = AcpProjectService(project)
        try {
            val fakeService = FakeAcpAgentService(
                descriptor = BuiltInAcpAgentRegistry.defaultAgent().toDescriptor()
            )
            projectService.replaceAgentServiceFactoryForTests(
                factory = { _, _, _, _ -> fakeService }
            )

            val items = mutableListOf<ToolWindowConversationItem>()
            val states = mutableListOf<ToolWindowComposerState>()
            val controller = AcpToolWindowController(
                projectService = projectService,
                appendItem = { items += it },
                updateItem = { _, item ->
                    items.removeAll { existing -> existing.itemId == item.itemId }
                    items += item
                },
                setComposerState = { states += it },
                uiExecutor = { it() },
            )
            try {
                controller.submitPrompt("hello ACP")
                waitForCondition {
                    items.any { it is ToolWindowConversationItem.AssistantText && it.text == "hello from agent" }
                }

                assertTrue(items.any { it is ToolWindowConversationItem.UserText && it.text == "hello ACP" })
                assertTrue(items.any { it is ToolWindowConversationItem.AssistantText && it.text == "hello from agent" })
                assertContainsElements(states, ToolWindowComposerState.CONNECTING, ToolWindowComposerState.SENDING)
            } finally {
                controller.dispose()
            }
        } finally {
            projectService.dispose()
        }
    }

    private fun waitForCondition(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(20)
        }
        fail("Condition was not met within $timeoutMillis ms")
    }

    private fun AcpProjectService.replaceAgentServiceFactoryForTests(
        factory: (project: com.intellij.openapi.project.Project, descriptor: AcpAgentDescriptor, parentScope: CoroutineScope, runtimeConnector: AcpRuntimeConnector) -> AcpAgentService
    ) {
        replaceAgentServiceFactoryForTests(object : com.github.ponyhuang.agentacpplugin.services.AcpAgentServiceFactory {
            override fun create(
                project: com.intellij.openapi.project.Project,
                descriptor: AcpAgentDescriptor,
                parentScope: CoroutineScope,
                runtimeConnector: AcpRuntimeConnector,
            ): AcpAgentService = factory(project, descriptor, parentScope, runtimeConnector)
        })
    }

    private class FakeAcpAgentService(
        override val descriptor: AcpAgentDescriptor,
    ) : AcpAgentService {
        private val _sessionUpdates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 16)

        override val sessionUpdates: SharedFlow<SessionUpdate> = _sessionUpdates.asSharedFlow()
        override val isConnected: Boolean = false

        override suspend fun connect() {
            // No-op - connection always succeeds if no exception is thrown
        }

        override fun sendPrompt(text: String): Flow<Event> {
            _sessionUpdates.tryEmit(
                SessionUpdate.AgentMessageChunk(ContentBlock.Text("hello from agent"))
            )
            return kotlinx.coroutines.flow.emptyFlow()
        }

        override suspend fun disconnect(reason: String?) = Unit

        override fun dispose() = Unit
    }
}
