package github.ponyhuang.acpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.ui.chat.PlanEntriesPanel
import github.ponyhuang.acpplugin.toolwindow.ui.composer.ComposerInputPanel
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import javax.swing.JPanel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(UnstableApi::class)
class ToolWindowStateBinderTest : BasePlatformTestCase() {

    fun testBinderProjectsPlanEntriesToPlanPanel() {
        val fixture = createFixture()
        try {
            fixture.sessionService.clearMessages()
            fixture.sessionService.applySessionUpdate(
                SessionUpdate.PlanUpdate(
                    entries = listOf(
                        PlanEntry(
                            content = "Inspect files",
                            priority = PlanEntryPriority.HIGH,
                            status = PlanEntryStatus.IN_PROGRESS
                        )
                    )
                )
            )

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertTrue(fixture.planEntriesPanel.isVisible)
            assertTrue(fixture.planEntriesPanel.hasEntries())
        } finally {
            fixture.dispose()
        }
    }

    fun testBinderProjectsUsageSummaryToPlanPanel() {
        val fixture = createFixture()
        try {
            fixture.sessionService.clearMessages()
            fixture.sessionService.applySessionUpdate(
                SessionUpdate.UsageUpdate(
                    used = 120,
                    size = 800,
                    cost = Cost(amount = 0.42, currency = "USD")
                )
            )

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertTrue(fixture.planEntriesPanel.isVisible)
            assertFalse(fixture.planEntriesPanel.hasEntries())
            assertTrue(fixture.planEntriesPanel.hasUsage())
        } finally {
            fixture.dispose()
        }
    }

    private fun createFixture(): BinderFixture {
        val sessionService = project.getService(AcpSessionService::class.java)
        val userInputPanel = ComposerInputPanel(project = project, agentItems = emptyList())
        val planEntriesPanel = PlanEntriesPanel()
        val composerContainer = JPanel(BorderLayout()).apply {
            add(planEntriesPanel, BorderLayout.NORTH)
            add(userInputPanel, BorderLayout.CENTER)
        }
        val toolbarController = ToolbarController(
            project = project,
            loading = sessionService.isLoading,
            connected = sessionService.isConnected,
            switching = MutableStateFlow(false),
            listingSessions = MutableStateFlow(false),
            isLoading = { false },
            isListingSessions = { false },
            hasSelectedAgent = { false },
            isSessionConnected = { sessionService.isConnected.value },
            getComposerState = { ToolWindowComposerState.IDLE },
            onNewSession = {},
            onShowSessions = {},
            onCancel = {}
        )
        val binder = ToolWindowStateBinder(
            sessionService = sessionService,
            switching = MutableStateFlow(false),
            currentAgentId = MutableStateFlow(null),
            configChanges = MutableSharedFlow<Unit>(),
            userInputPanel = userInputPanel,
            toolbarController = toolbarController,
            planEntriesPanel = planEntriesPanel,
            composerContainer = composerContainer,
            buildAgentItems = { emptyList() }
        )
        return BinderFixture(
            sessionService = sessionService,
            userInputPanel = userInputPanel,
            toolbarController = toolbarController,
            binder = binder,
            planEntriesPanel = planEntriesPanel
        )
    }

    private data class BinderFixture(
        val sessionService: AcpSessionService,
        val userInputPanel: ComposerInputPanel,
        val toolbarController: ToolbarController,
        val binder: ToolWindowStateBinder,
        val planEntriesPanel: PlanEntriesPanel
    ) {
        fun dispose() {
            binder.dispose()
            toolbarController.dispose()
            userInputPanel.dispose()
            sessionService.clearMessages()
        }
    }
}
