package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.MyBundle
import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

internal class ToolCallRow(
    private val project: Project,
    toolCall: AcpSessionService.ToolCallInfo
) : JPanel(), Disposable {
    private val titleLabel = JBLabel()
    private val openDiffLink = ActionLink(MyBundle.message("toolcall.diff.openPreview")) {
        openAllDiffPreviews()
    }.apply {
        isVisible = false
        border = JBUI.Borders.emptyRight(8)
    }
    private val statusLabel = ToolStatusLabel(toolCall.status)
    private val detailsPanel = JPanel()
    private val diffContainer = JPanel()
    private var currentDiffContents: List<AcpSessionService.ToolCallDiffInfo> = emptyList()

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
                    JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        isOpaque = false
                        add(
                            titleLabel.apply {
                                foreground = UIUtil.getLabelForeground()
                            },
                            BorderLayout.CENTER
                        )
                        add(openDiffLink, BorderLayout.EAST)
                    },
                    BorderLayout.CENTER
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

        diffContainer.layout = BoxLayout(diffContainer, BoxLayout.Y_AXIS)
        diffContainer.isOpaque = false
        diffContainer.alignmentX = LEFT_ALIGNMENT
        chrome.contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        chrome.contentPanel.add(diffContainer)
        update(toolCall)
    }

    fun update(toolCall: AcpSessionService.ToolCallInfo) {
        titleLabel.text = "${toolKindDisplay(toolCall.kind)} ${toolCall.title}"
        statusLabel.updateStatus(toolCall.status)
        openDiffLink.isVisible = toolCall.diffContents.isNotEmpty()
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
        rebuildDiffPreviews(toolCall.diffContents)
        revalidate()
        repaint()
    }

    private fun rebuildDiffPreviews(diffContents: List<AcpSessionService.ToolCallDiffInfo>) {
        currentDiffContents = diffContents
        diffContainer.removeAll()

        if (diffContents.isEmpty()) {
            diffContainer.isVisible = false
            return
        }
        diffContainer.isVisible = true

        diffContents.forEachIndexed { index, diff ->
            if (index > 0) {
                diffContainer.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
            diffContainer.add(createDiffPreviewComponent(diff))
        }
    }

    private fun createDiffPreviewComponent(diff: AcpSessionService.ToolCallDiffInfo): JComponent {
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            border = JBUI.Borders.compound(JBUI.Borders.emptyTop(4), BorderFactory.createLineBorder(UIUtil.getBoundsColor()))
            add(
                JBLabel(diff.path).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(6, 8)
                },
                BorderLayout.CENTER
            )
            add(
                ActionLink(MyBundle.message("toolcall.diff.openPreview")) {
                    openDiffPreview(diff)
                }.apply {
                    border = JBUI.Borders.empty(6, 8)
                },
                BorderLayout.EAST
            )
        }
    }

    private fun openDiffPreview(diff: AcpSessionService.ToolCallDiffInfo) {
        val contentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            diff.path,
            contentFactory.create(project, diff.oldText.orEmpty()),
            contentFactory.create(project, diff.newText),
            MyBundle.message("toolcall.diff.before"),
            MyBundle.message("toolcall.diff.after")
        )
        DiffManager.getInstance().showDiff(project, request, DiffDialogHints.FRAME)
    }

    private fun openAllDiffPreviews() {
        currentDiffContents.firstOrNull()?.let(::openDiffPreview)
    }

    override fun dispose() = Unit
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
