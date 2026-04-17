package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
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
import javax.swing.*

class PlanEntriesPanel : JPanel(BorderLayout()) {

    private val titleLabel = JBLabel("Latest Plan")
    private val countLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val summaryLabel = JBLabel().apply {
        foreground = UIUtil.getLabelForeground()
    }

    private var entries: List<AcpSessionService.SessionPlanItem> = emptyList()
    private var popup: JBPopup? = null
    private var popupContent: JComponent? = null
    private var isHoveringPanel = false
    private var isHoveringPopup = false

    private val showPopupTimer = Timer(200) {
        if (isHoveringPanel && entries.isNotEmpty()) {
            showPopup()
        }
    }.apply {
        isRepeats = false
    }

    private val hidePopupTimer = Timer(250) {
        if (!isHoveringPanel && !isHoveringPopup) {
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

        add(createHeader(), BorderLayout.CENTER)
        addMouseListener(createHoverMouseListener())
        addMouseMotionListener(createHoverMouseListener())
        refreshUi()
    }

    fun updatePlanEntries(entries: List<AcpSessionService.SessionPlanItem>) {
        this.entries = entries
        refreshUi()
        if (entries.isEmpty()) {
            hidePopup()
            return
        }

        if (popup?.isVisible == true && (isHoveringPanel || isHoveringPopup)) {
            showPopup()
        }
    }

    fun hasEntries(): Boolean = entries.isNotEmpty()

    fun popupIsVisible(): Boolean = popup?.isVisible == true

    fun createPopupContentForTest(): JComponent = createPopupContent()

    override fun removeNotify() {
        hidePopup()
        super.removeNotify()
    }

    private fun createHeader(): JComponent {
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(titleLabel)
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                    add(countLabel)
                },
                BorderLayout.WEST
            )
            add(summaryLabel, BorderLayout.CENTER)
        }
    }

    private fun refreshUi() {
        countLabel.text = if (entries.isEmpty()) "" else "${entries.size} items"
        summaryLabel.text = entries.firstOrNull()?.content ?: ""
        toolTipText = if (entries.isEmpty()) null else "Hover to preview the latest plan"
        isVisible = entries.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun showPopup() {
        hidePopup()
        val content = createPopupContent()
        popupContent = content
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .createPopup()

        val location = Point(0, height)
        popup?.show(RelativePoint(this, location))
    }

    private fun hidePopup() {
        popup?.cancel()
        popup = null
        popupContent = null
        isHoveringPopup = false
    }

    private fun createPopupContent(): JComponent {
        val entryListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }
        entries.forEachIndexed { index, entry ->
            entryListPanel.add(PlanEntryRow(entry))
            if (index != entries.lastIndex) {
                entryListPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
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
                isHoveringPanel = true
                hidePopupTimer.stop()
                if (popup?.isVisible != true) {
                    showPopupTimer.restart()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                isHoveringPanel = false
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

private class PlanEntryRow(entry: AcpSessionService.SessionPlanItem) : JBPanel<PlanEntryRow>(BorderLayout()) {
    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)

        add(
            JBLabel(entry.content).apply {
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(4)
            },
            BorderLayout.CENTER
        )
        add(
            JBLabel("${entry.status.replace('_', ' ')} | ${entry.priority}").apply {
                foreground = UIUtil.getContextHelpForeground()
            },
            BorderLayout.SOUTH
        )
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = LEFT_ALIGNMENT
    }
}
