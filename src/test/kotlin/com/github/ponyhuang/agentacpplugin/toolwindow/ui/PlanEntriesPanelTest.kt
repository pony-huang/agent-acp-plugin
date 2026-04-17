package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel

class PlanEntriesPanelTest : BasePlatformTestCase() {

    fun testUpdatePlanEntriesRendersSummaryAndCount() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(
            listOf(
                AcpSessionService.SessionPlanItem(
                    content = "Inspect files",
                    priority = "high",
                    status = "in_progress"
                ),
                AcpSessionService.SessionPlanItem(
                    content = "Render panel",
                    priority = "medium",
                    status = "pending"
                )
            )
        )

        val countLabel = readComponent<JLabel>(panel, "countLabel")
        val summaryLabel = readComponent<JLabel>(panel, "summaryLabel")

        assertTrue(panel.isVisible)
        assertEquals("2 items", countLabel.text)
        assertEquals("Inspect files", summaryLabel.text)
    }

    fun testEmptyEntriesHidePanelAndClearSummary() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(emptyList())

        val countLabel = readComponent<JLabel>(panel, "countLabel")
        val summaryLabel = readComponent<JLabel>(panel, "summaryLabel")

        assertFalse(panel.isVisible)
        assertEquals("", countLabel.text)
        assertEquals("", summaryLabel.text)
    }

    fun testPopupContentRendersAllEntries() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(
            listOf(
                AcpSessionService.SessionPlanItem(
                    content = "Inspect files",
                    priority = "high",
                    status = "in_progress"
                ),
                AcpSessionService.SessionPlanItem(
                    content = "Render panel",
                    priority = "medium",
                    status = "pending"
                )
            )
        )

        val popupContent = panel.createPopupContentForTest()

        assertNotNull(findLabel(popupContent, "Inspect files"))
        assertNotNull(findLabel(popupContent, "in progress | high"))
        assertNotNull(findLabel(popupContent, "Render panel"))
        assertNotNull(findLabel(popupContent, "pending | medium"))
    }

    private inline fun <reified T> readComponent(panel: PlanEntriesPanel, fieldName: String): T {
        return panel.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(panel) as T
    }

    private fun findLabel(root: Container, text: String): JLabel? {
        root.components.forEach { child ->
            if (child is JLabel && child.text == text) {
                return child
            }
            if (child is Container) {
                val nested = findLabel(child, text)
                if (nested != null) {
                    return nested
                }
            }
        }
        return null
    }
}
