package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Timer

internal class ToolCallRow(toolCall: AcpSessionService.ToolCallInfo) : JPanel() {
    private val titleLabel = JBLabel()
    private val statusLabel = ToolStatusLabel(toolCall.status)
    private val detailsPanel = JPanel()

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)
        chrome.contentPanel.layout = BoxLayout(chrome.contentPanel, BoxLayout.Y_AXIS)

        chrome.contentPanel.add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(
                    titleLabel.apply {
                        foreground = UIUtil.getLabelForeground()
                    },
                    BorderLayout.WEST
                )
                add(statusLabel, BorderLayout.EAST)
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
        )

        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.isOpaque = false
        chrome.contentPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        chrome.contentPanel.add(detailsPanel)
        update(toolCall)
    }

    fun update(toolCall: AcpSessionService.ToolCallInfo) {
        titleLabel.text = "${toolKindDisplay(toolCall.kind)} ${toolCall.title}"
        statusLabel.updateStatus(toolCall.status)
        detailsPanel.removeAll()
        val details = buildList {
            toolCall.locations.firstOrNull()?.let { add(it) }
            toolCall.contentSummary?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        details.forEachIndexed { index, line ->
            detailsPanel.add(
                JBLabel(line).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = if (index == 0) JBUI.Borders.empty() else JBUI.Borders.emptyTop(2)
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        }
        revalidate()
        repaint()
    }
}

internal class ToolStatusLabel(status: String) : JPanel(BorderLayout(JBUI.scale(4), 0)) {
    private val statusIcon = ToolStatusIcon(status)
    private val statusText = JBLabel(status.toDisplayLabel()).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        isOpaque = false
        add(statusText, BorderLayout.WEST)
        add(statusIcon, BorderLayout.EAST)
    }

    fun updateStatus(status: String) {
        statusText.text = status.toDisplayLabel()
        statusIcon.updateStatus(status)
        revalidate()
        repaint()
    }
}

internal class ToolStatusIcon(status: String) : JBLabel() {
    private val animatedIcons = arrayOf(
        AllIcons.Process.Step_1,
        AllIcons.Process.Step_2,
        AllIcons.Process.Step_3,
        AllIcons.Process.Step_4,
        AllIcons.Process.Step_5,
        AllIcons.Process.Step_6,
        AllIcons.Process.Step_7,
        AllIcons.Process.Step_8
    )
    private val animationTimer = Timer(60, null)
    private var shouldAnimate = status == "in_progress"
    private var animationFrame = 0

    init {
        isOpaque = false
        border = JBUI.Borders.emptyLeft(4)
        if (shouldAnimate) {
            animationTimer.addActionListener {
                animationFrame = (animationFrame + 1) % animatedIcons.size
                icon = animatedIcons[animationFrame]
                repaint()
            }
            animationTimer.isRepeats = true
        }
        updateStatus(status)
    }

    override fun addNotify() {
        super.addNotify()
        if (shouldAnimate && !animationTimer.isRunning) {
            animationFrame = 0
            icon = animatedIcons[animationFrame]
            animationTimer.start()
        }
    }

    override fun removeNotify() {
        animationTimer.stop()
        super.removeNotify()
    }

    fun updateStatus(status: String) {
        shouldAnimate = status == "in_progress"
        if (shouldAnimate) {
            animationFrame = 0
            icon = animatedIcons[animationFrame]
            if (isDisplayable && !animationTimer.isRunning) {
                animationTimer.start()
            }
        } else {
            animationTimer.stop()
            icon = statusIconFor(status)
        }
        repaint()
    }
}
