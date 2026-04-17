package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class PlanEntriesPanelTest : BasePlatformTestCase() {

    fun testUpdatePlanEntriesRendersRowsAndCount() {
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

        val entryListPanel = readComponent<JPanel>(panel, "entryListPanel")
        val countLabel = readComponent<JLabel>(panel, "countLabel")

        assertEquals("2 items", countLabel.text)
        assertEquals(3, entryListPanel.componentCount)
        assertNotNull(findLabel(entryListPanel, "Inspect files"))
        assertNotNull(findLabel(entryListPanel, "in progress | high"))
    }

    fun testCollapsedPanelHidesScrollableBodyButKeepsHeaderVisible() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(
            listOf(
                AcpSessionService.SessionPlanItem(
                    content = "Inspect files",
                    priority = "high",
                    status = "in_progress"
                )
            )
        )

        panel.setExpanded(false)

        val scrollPane = readComponent<JComponent>(panel, "scrollPane")
        val titleLabel = readComponent<JLabel>(panel, "titleLabel")

        assertFalse(panel.isExpanded())
        assertFalse(scrollPane.isVisible)
        assertEquals("Latest Plan", titleLabel.text)
    }

    private inline fun <reified T> readComponent(panel: PlanEntriesPanel, fieldName: String): T {
        return panel.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(panel) as T
    }

    private fun findLabel(root: JComponent, text: String): JLabel? {
        if (root is JLabel && root.text == text) {
            return root
        }
        root.components.forEach { child ->
            if (child is JComponent) {
                val match = findLabel(child, text)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }
}
