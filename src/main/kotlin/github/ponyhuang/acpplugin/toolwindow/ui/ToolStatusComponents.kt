package github.ponyhuang.acpplugin.toolwindow.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.HelpTooltip
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.Timer

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

    fun updateStatus(status: String, failureSummary: String? = null) {
        statusText.text = status.toDisplayLabel()
        statusIcon.updateStatus(status, failureSummary)
        toolTipText = statusIcon.toolTipText
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
        HelpTooltip.dispose(this)
        super.removeNotify()
    }

    fun updateStatus(status: String, failureSummary: String? = null) {
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
        updateFailedTooltip(status, failureSummary)
        repaint()
    }

    private fun updateFailedTooltip(status: String, failureSummary: String?) {
        HelpTooltip.dispose(this)
        toolTipText = null
        if (status != "failed") {
            return
        }
        val rawText = failureSummary?.takeIf { it.isNotBlank() } ?: return
        val description = helpTooltipDescriptionFor(rawText)
        toolTipText = standardTooltipFor(rawText)
        HelpTooltip()
            .setTitle(status.toDisplayLabel())
            .setDescription(description)
            .installOn(this)
    }

    private fun helpTooltipDescriptionFor(text: String): String {
        return StringUtil.escapeXmlEntities(text.trim())
            .replace("\r\n", "\n")
            .replace("\n\n", "<p>")
            .replace("\n", "<br/>")
    }

    private fun standardTooltipFor(text: String): String {
        return "<html>${helpTooltipDescriptionFor(text)}</html>"
    }
}
