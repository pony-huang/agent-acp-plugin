package github.ponyhuang.acpplugin.toolwindow

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import github.ponyhuang.acpplugin.services.AcpSessionService
import github.ponyhuang.acpplugin.toolwindow.ui.composer.ComposerInputPanel
import github.ponyhuang.acpplugin.toolwindow.ui.chat.PlanEntriesPanel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.awt.Container

@OptIn(UnstableApi::class)
class AcpToolWindowPanelTest : BasePlatformTestCase() {

    fun testToolWindowPanelInstallsToolbarComponent() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)

            assertNotNull(panel.toolbar)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelUsesBorderLayoutComposerContainer() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)

            val composerContainer = readField<Container>(panel, "composerContainer")
            val planEntriesPanel = readField<PlanEntriesPanel>(panel, "planEntriesPanel")
            val userInputPanel = readField<ComposerInputPanel>(panel, "userInputPanel")
            val layout = composerContainer.layout as BorderLayout

            assertSame(planEntriesPanel, layout.getLayoutComponent(BorderLayout.NORTH))
            assertSame(userInputPanel, layout.getLayoutComponent(BorderLayout.CENTER))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testCreateNewSessionWithoutSelectedAgentDoesNotThrow() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)

            panel.createNewSession()

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelShowsPlanPanelWhenPlanEntriesArrive() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)
            val planEntriesPanel = readField<PlanEntriesPanel>(panel, "planEntriesPanel")

            sessionService.applySessionUpdate(
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

            assertTrue(planEntriesPanel.isVisible)
            assertTrue(planEntriesPanel.hasEntries())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelShowsTopPanelWhenOnlyUsageExists() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)
            val planEntriesPanel = readField<PlanEntriesPanel>(panel, "planEntriesPanel")

            sessionService.applySessionUpdate(
                SessionUpdate.UsageUpdate(
                    used = 120,
                    size = 800,
                    cost = Cost(amount = 0.42, currency = "USD")
                )
            )

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertTrue(planEntriesPanel.isVisible)
            assertFalse(planEntriesPanel.hasEntries())
            assertTrue(planEntriesPanel.hasUsage())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelKeepsComposerInputPanelMountedAcrossPlanUpdates() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)
            val composerContainer = readField<Container>(panel, "composerContainer")
            val userInputPanel = readField<ComposerInputPanel>(panel, "userInputPanel")

            sessionService.applySessionUpdate(
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
            sessionService.applySessionUpdate(
                SessionUpdate.PlanUpdate(
                    entries = listOf(
                        PlanEntry(
                            content = "Render panel",
                            priority = PlanEntryPriority.MEDIUM,
                            status = PlanEntryStatus.PENDING
                        )
                    )
                )
            )

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val layout = composerContainer.layout as BorderLayout
            assertSame(userInputPanel, layout.getLayoutComponent(BorderLayout.CENTER))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelHidesPlanPanelWhenPlanEntriesAreCleared() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)
            val composerContainer = readField<Container>(panel, "composerContainer")
            val planEntriesPanel = readField<PlanEntriesPanel>(panel, "planEntriesPanel")
            val userInputPanel = readField<ComposerInputPanel>(panel, "userInputPanel")

            sessionService.applySessionUpdate(
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
            sessionService.clearMessages()

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val layout = composerContainer.layout as BorderLayout
            assertFalse(planEntriesPanel.isVisible)
            assertSame(userInputPanel, layout.getLayoutComponent(BorderLayout.CENTER))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelHidesTopPanelWhenPlanAndUsageAreCleared() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)
            val planEntriesPanel = readField<PlanEntriesPanel>(panel, "planEntriesPanel")

            sessionService.applySessionUpdate(
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
            sessionService.applySessionUpdate(
                SessionUpdate.UsageUpdate(
                    used = 120,
                    size = 800,
                    cost = null
                )
            )
            sessionService.clearMessages()

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertFalse(planEntriesPanel.isVisible)
            assertFalse(planEntriesPanel.hasEntries())
            assertFalse(planEntriesPanel.hasUsage())
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testBuildLoadedSessionNotificationContentIncludesTitleAndSessionId() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)

            val content = panel.buildLoadedSessionNotificationContent(
                AcpSessionService.SessionListItem(
                    sessionId = "session-123",
                    title = "Read file",
                    cwd = project.basePath ?: ".",
                    updatedAtMillis = null
                )
            )

            assertEquals(
                "<html>Read file (sessionId: session-123)</html>",
                content
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private inline fun <reified T> readField(target: Any, fieldName: String): T {
        return target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(target) as T
    }
}
