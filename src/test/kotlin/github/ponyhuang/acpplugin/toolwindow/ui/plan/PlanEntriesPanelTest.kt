package github.ponyhuang.acpplugin.toolwindow.ui.plan

import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Container
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class PlanEntriesPanelTest : BasePlatformTestCase() {

    fun testUpdatePlanEntriesRendersSummaryAndCount() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(
            listOf(
                AcpSessionService.SessionPlanItem(
                    content = "Completed prep",
                    priority = "high",
                    status = "completed"
                ),
                AcpSessionService.SessionPlanItem(
                    content = "Render panel",
                    priority = "medium",
                    status = "in_progress"
                ),
                AcpSessionService.SessionPlanItem(
                    content = "Inspect files",
                    priority = "high",
                    status = "pending"
                )
            )
        )

        val countLabel = readComponent<JLabel>(panel, "countLabel")
        val summaryLabel = readComponent<JLabel>(panel, "summaryLabel")
        val usageLabel = readComponent<JLabel>(panel, "usageLabel")

        assertTrue(panel.isVisible)
        assertEquals("3 items", countLabel.text)
        assertEquals("Render panel", summaryLabel.text)
        assertFalse(usageLabel.isVisible)
    }

    fun testSummaryFallsBackToPendingWhenNoTaskIsInProgress() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(
            listOf(
                AcpSessionService.SessionPlanItem(
                    content = "Completed prep",
                    priority = "high",
                    status = "completed"
                ),
                AcpSessionService.SessionPlanItem(
                    content = "Inspect files",
                    priority = "medium",
                    status = "pending"
                )
            )
        )

        val summaryLabel = readComponent<JLabel>(panel, "summaryLabel")

        assertEquals("Inspect files", summaryLabel.text)
    }

    fun testEmptyEntriesHidePanelAndClearSummary() {
        val panel = PlanEntriesPanel()
        panel.updatePlanEntries(emptyList())

        val countLabel = readComponent<JLabel>(panel, "countLabel")
        val summaryLabel = readComponent<JLabel>(panel, "summaryLabel")
        val usageLabel = readComponent<JLabel>(panel, "usageLabel")

        assertFalse(panel.isVisible)
        assertEquals("", countLabel.text)
        assertEquals("", summaryLabel.text)
        assertEquals("", usageLabel.text)
    }

    fun testUsageAppearsOnRightSideWhenPresent() {
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
        panel.updateLatestUsage(
            AcpSessionService.SessionUsageSummary(
                usedTokens = 120,
                totalTokens = 800,
                costAmount = 0.42,
                costCurrency = "USD"
            )
        )

        val usageLabel = readComponent<JLabel>(panel, "usageLabel")

        assertTrue(usageLabel.isVisible)
        assertEquals("Tokens 120/800  Cost 0.42 USD", usageLabel.text)
    }

    fun testUsageKeepsTopPanelVisibleWithoutPlanEntries() {
        val panel = PlanEntriesPanel()
        panel.updateLatestUsage(
            AcpSessionService.SessionUsageSummary(
                usedTokens = 120,
                totalTokens = 800,
                costAmount = null,
                costCurrency = null
            )
        )

        val planSummaryPanel = readComponent<JComponent>(panel, "planSummaryPanel")
        val usageLabel = readComponent<JLabel>(panel, "usageLabel")

        assertTrue(panel.isVisible)
        assertFalse(planSummaryPanel.isVisible)
        assertTrue(usageLabel.isVisible)
        assertEquals("Tokens 120/800", usageLabel.text)
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

    fun testPopupContentUsesCompactEntrySpacingAndPadding() {
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

        val popupContent = panel.createPopupContentForTest() as JBScrollPane
        val entryListPanel = popupContent.viewport.view as JPanel
        val entryRows = entryListPanel.components.filter { it.javaClass.simpleName == "PlanEntryRow" }
        val spacer = entryListPanel.components.filterIsInstance<Box.Filler>().single()
        val titleLabel = findLabel(entryRows.first() as Container, "Inspect files")

        assertEquals(2, entryRows.size)
        assertEquals(JBUI.scale(4), spacer.preferredSize.height)
        assertNotNull(titleLabel)
        val rowInsets = (entryRows.first() as JComponent).border.getBorderInsets(entryRows.first() as JComponent)
        assertEquals(JBUI.scale(6), rowInsets.top)
        assertEquals(JBUI.scale(6), rowInsets.bottom)
        assertEquals(JBUI.scale(8), rowInsets.left)
        assertEquals(JBUI.scale(8), rowInsets.right)
        val titleInsets = titleLabel!!.border.getBorderInsets(titleLabel)
        assertEquals(JBUI.scale(3), titleInsets.bottom)
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
