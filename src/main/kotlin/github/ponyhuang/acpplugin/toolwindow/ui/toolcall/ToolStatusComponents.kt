package github.ponyhuang.acpplugin.toolwindow.ui.toolcall

import com.intellij.ide.HelpTooltip
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import github.ponyhuang.acpplugin.toolwindow.ui.AnimatedStatusLabel
import github.ponyhuang.acpplugin.toolwindow.ui.ProcessStepIcons
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JPanel

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

internal class ToolStatusIcon(status: String) : AnimatedStatusLabel(ProcessStepIcons.icons) {
    private var currentStatus: String = status

    init {
        border = JBUI.Borders.emptyLeft(4)
        updateStatus(status)
    }

    override fun staticIcon(): Icon = statusIconFor(currentStatus)

    override fun removeNotify() {
        super.removeNotify()
        HelpTooltip.dispose(this)
    }

    fun updateStatus(status: String, failureSummary: String? = null) {
        currentStatus = status
        updateAnimation(status == "in_progress")
        updateFailedTooltip(status, failureSummary)
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
