package github.ponyhuang.acpplugin.toolwindow.ui.chat

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension

internal class PlanEntryRow(
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
