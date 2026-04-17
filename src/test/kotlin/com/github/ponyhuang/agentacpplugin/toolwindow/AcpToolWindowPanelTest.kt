package com.github.ponyhuang.agentacpplugin.toolwindow

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container

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

    fun testToolWindowPanelShowsPlanComposerSectionWhenPlanEntriesArrive() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)

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

            val composerContainer = readField<Container>(panel, "composerContainer")
            assertEquals(1, composerContainer.componentCount)
            assertEquals(
                "com.intellij.openapi.ui.Splitter",
                composerContainer.components.single().javaClass.name
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolWindowPanelFallsBackToUserInputWhenPlanEntriesAreCleared() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = AcpToolWindowPanel(project, disposable)
            val sessionService = project.getService(AcpSessionService::class.java)

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

            val composerContainer = readField<Container>(panel, "composerContainer")
            assertEquals(1, composerContainer.componentCount)
            assertEquals(
                "com.github.ponyhuang.agentacpplugin.toolwindow.ui.AcpUserInputPanel",
                composerContainer.components.single().javaClass.name
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
