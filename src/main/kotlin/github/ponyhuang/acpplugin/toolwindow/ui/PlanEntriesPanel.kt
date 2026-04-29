package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class PlanEntriesPanel : JPanel(BorderLayout()) {
    private val popupItemGap = JBUI.scale(4)
    private val popupItemVerticalPadding = JBUI.scale(6)
    private val popupItemHorizontalPadding = JBUI.scale(8)
    private val popupStatusTopGap = JBUI.scale(3)

    private val headerPanel = JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8)
    }
    private val planSummaryPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
    }
    private val titleLabel = JBLabel(MyBundle.message("plan.latestPlan"))
    private val countLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val summaryLabel = JBLabel().apply {
        foreground = UIUtil.getLabelForeground()
    }
    private val usageLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    private var entries: List<AcpSessionService.SessionPlanItem> = emptyList()
    private var latestUsage: AcpSessionService.SessionUsageSummary? = null
    private var popup: JBPopup? = null
    private var isHoveringPlanPanel = false
    private var isHoveringPopup = false

    private val showPopupTimer = Timer(200) {
        if (isHoveringPlanPanel && entries.isNotEmpty()) {
            showPopup()
        }
    }.apply {
        isRepeats = false
    }

    private val hidePopupTimer = Timer(250) {
        if (!isHoveringPlanPanel && !isHoveringPopup) {
            hidePopup()
        }
    }.apply {
        isRepeats = false
    }

    init {
        isOpaque = true
        background = JBColor(
            ColorUtil.mix(UIUtil.getPanelBackground(), JBColor(0xF2F7ED, 0x243126), 0.88),
            ColorUtil.mix(UIUtil.getPanelBackground(), JBColor(0xF2F7ED, 0x243126), 0.52)
        )
        border = JBUI.Borders.empty(0, 0, 4, 0)

        val titlePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(titleLabel)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(countLabel)
        }
        planSummaryPanel.add(titlePanel, BorderLayout.WEST)
        planSummaryPanel.add(summaryLabel, BorderLayout.CENTER)

        headerPanel.add(planSummaryPanel, BorderLayout.CENTER)
        headerPanel.add(usageLabel, BorderLayout.EAST)
        add(headerPanel, BorderLayout.CENTER)

        val hoverListener = createHoverMouseListener()
        listOf(planSummaryPanel, titleLabel, countLabel, summaryLabel).forEach { component ->
            component.addMouseListener(hoverListener)
            component.addMouseMotionListener(hoverListener)
        }

        refreshUi()
    }

    fun updatePlanEntries(entries: List<AcpSessionService.SessionPlanItem>) {
        this.entries = entries
        refreshUi()
        if (entries.isEmpty()) {
            hidePopup()
            return
        }

        if (popup?.isVisible == true && (isHoveringPlanPanel || isHoveringPopup)) {
            showPopup()
        }
    }

    fun updateLatestUsage(usage: AcpSessionService.SessionUsageSummary?) {
        latestUsage = usage
        refreshUi()
    }

    fun hasEntries(): Boolean = entries.isNotEmpty()

    fun hasUsage(): Boolean = latestUsage != null

    fun popupIsVisible(): Boolean = popup?.isVisible == true

    fun createPopupContentForTest(): JComponent = createPopupContent()

    override fun removeNotify() {
        hidePopup()
        super.removeNotify()
    }

    private fun refreshUi() {
        countLabel.text = if (entries.isEmpty()) "" else MyBundle.message("plan.itemsCount", entries.size)
        summaryLabel.text = currentSummaryEntry()?.content.orEmpty()
        usageLabel.text = latestUsage?.let(::formatUsageSummary).orEmpty()

        planSummaryPanel.isVisible = entries.isNotEmpty()
        usageLabel.isVisible = latestUsage != null
        planSummaryPanel.toolTipText = if (entries.isEmpty()) null else MyBundle.message("plan.hoverToPreview")
        isVisible = entries.isNotEmpty() || latestUsage != null
        revalidate()
        repaint()
    }

    private fun currentSummaryEntry(): AcpSessionService.SessionPlanItem? {
        return entries.firstOrNull { it.status == "in_progress" }
            ?: entries.firstOrNull { it.status == "pending" }
            ?: entries.firstOrNull()
    }

    private fun formatUsageSummary(usage: AcpSessionService.SessionUsageSummary): String {
        val tokenSummary = MyBundle.message("plan.tokens", usage.usedTokens, usage.totalTokens)
        val costSummary = usage.costAmount?.let { amount ->
            buildString {
                append(MyBundle.message("plan.cost"))
                append(' ')
                append(amount)
                usage.costCurrency?.takeIf { it.isNotBlank() }?.let { currency ->
                    append(' ')
                    append(currency)
                }
            }
        }
        return listOfNotNull(tokenSummary, costSummary).joinToString("  ")
    }

    private fun showPopup() {
        hidePopup()
        val content = createPopupContent()
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        val location = Point(0, planSummaryPanel.height)
        popup?.show(RelativePoint(planSummaryPanel, location))
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
        isHoveringPopup = false
    }

    private fun createPopupContent(): JComponent {
        val entryListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }
        entries.forEachIndexed { index, entry ->
            entryListPanel.add(
                PlanEntryRow(
                    entry = entry,
                    verticalPadding = popupItemVerticalPadding,
                    horizontalPadding = popupItemHorizontalPadding,
                    statusTopGap = popupStatusTopGap
                )
            )
            if (index != entries.lastIndex) {
                entryListPanel.add(Box.createVerticalStrut(popupItemGap))
            }
        }

        return JBScrollPane(entryListPanel).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(220))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            viewport.isOpaque = false
            isOpaque = false

            val hoverListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isHoveringPopup = true
                    hidePopupTimer.stop()
                }

                override fun mouseExited(e: MouseEvent) {
                    isHoveringPopup = false
                    scheduleHidePopup()
                }
            }
            addMouseListener(hoverListener)
            viewport.addMouseListener(hoverListener)
            entryListPanel.addMouseListener(hoverListener)
        }
    }

    private fun createHoverMouseListener(): MouseAdapter {
        return object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (entries.isEmpty()) {
                    return
                }
                isHoveringPlanPanel = true
                hidePopupTimer.stop()
                if (popup?.isVisible != true) {
                    showPopupTimer.restart()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                isHoveringPlanPanel = false
                showPopupTimer.stop()
                scheduleHidePopup()
            }
        }
    }

    private fun scheduleHidePopup() {
        if (popup?.isVisible == true) {
            hidePopupTimer.restart()
        }
    }
}

private class PlanEntryRow(
    entry: AcpSessionService.SessionPlanItem,
    verticalPadding: Int,
    horizontalPadding: Int,
    statusTopGap: Int
) : JBPanel<PlanEntryRow>(BorderLayout()) {
    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(verticalPadding, horizontalPadding)

        add(
            JBLabel(entry.content).apply {
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(statusTopGap)
            },
            BorderLayout.CENTER
        )
        add(
            JBLabel(MyBundle.message("plan.entryStatus", entry.status.replace('_', ' '), entry.priority)).apply {
                foreground = UIUtil.getContextHelpForeground()
            },
            BorderLayout.SOUTH
        )
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = LEFT_ALIGNMENT
    }
}
