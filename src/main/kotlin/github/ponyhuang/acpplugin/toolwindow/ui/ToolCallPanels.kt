package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.ide.HelpTooltip
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

internal class ToolCallRow(
    private val project: Project,
    toolCall: AcpSessionService.ToolCallInfo
) : JPanel(), Disposable {
    private val titleLabel = JBLabel()
    private val titleLink = ActionLink("") {}
    private val titleContainer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
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
    private var titleLinkAction: ActionListener? = null

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)
        chrome.contentPanel.layout = BorderLayout()

        chrome.contentPanel.add(
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(
                    JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        isOpaque = false
                        add(
                            titleContainer.apply {
                                isOpaque = false
                                add(
                                    titleLabel.apply {
                                        foreground = UIUtil.getLabelForeground()
                                    }
                                )
                                add(
                                    titleLink.apply {
                                        isVisible = false
                                        border = JBUI.Borders.empty()
                                        alignmentX = LEFT_ALIGNMENT
                                    }
                                )
                            },
                            BorderLayout.CENTER
                        )
                        add(
                            openDiffLink,
                            BorderLayout.EAST
                        )
                    },
                    BorderLayout.CENTER
                )
                add(statusLabel, BorderLayout.EAST)
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            },
            BorderLayout.CENTER
        )
        detailsPanel.isOpaque = false
        detailsPanel.isVisible = false
        diffContainer.isOpaque = false
        diffContainer.isVisible = false
        update(toolCall)
    }

    fun update(toolCall: AcpSessionService.ToolCallInfo) {
        val primaryLocation = toolCall.locations.firstOrNull()
        val navigableFile = primaryLocation?.takeIf { toolCall.kind == "read" }?.let { resolveNavigableFile(it.path) }
        updateTitle(toolCall, primaryLocation, navigableFile)
        statusLabel.updateStatus(toolCall.status, toolCall.failureDetails ?: toolCall.contentSummary)
        openDiffLink.isVisible = toolCall.diffContents.isNotEmpty()
        detailsPanel.removeAll()
        currentDiffContents = toolCall.diffContents
        rebuildDiffPreviews(emptyList())
        revalidate()
        repaint()
    }

    private fun updateTitle(
        toolCall: AcpSessionService.ToolCallInfo,
        primaryLocation: AcpSessionService.ToolCallLocationInfo?,
        navigableFile: VirtualFile?
    ) {
        val kindDisplay = toolKindDisplay(toolCall.kind)
        titleLabel.icon = toolKindIcon(toolCall.kind)
        if (toolCall.kind == "read" && primaryLocation != null && navigableFile != null) {
            titleLabel.isVisible = true
            titleLabel.text = kindDisplay
            titleLink.isVisible = true
            titleLink.text = linkTextFor(primaryLocation, navigableFile)
            titleLinkAction?.let(titleLink::removeActionListener)
            titleLinkAction = ActionListener {
                navigateTo(primaryLocation, navigableFile)
            }
            titleLink.addActionListener(titleLinkAction)
        } else {
            titleLinkAction?.let(titleLink::removeActionListener)
            titleLinkAction = null
            titleLink.isVisible = false
            titleLabel.isVisible = true
            titleLabel.text = buildTitleText(toolCall.kind, toolCall.title)
        }
    }

    private fun createLocationComponent(
        toolKind: String?,
        location: AcpSessionService.ToolCallLocationInfo
    ): JComponent {
        val file = if (toolKind == "read") resolveNavigableFile(location.path) else null
        return if (file != null) {
            ActionLink(linkTextFor(location, file)) {
                navigateTo(location, file)
            }.apply {
                alignmentX = LEFT_ALIGNMENT
            }
        } else {
            createDetailLabel(location.displayText)
        }
    }

    private fun createDetailLabel(text: String): JBLabel {
        return JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun linkTextFor(
        location: AcpSessionService.ToolCallLocationInfo,
        file: VirtualFile
    ): String {
        return file.name.ifBlank {
            Path.of(location.path).fileName?.toString().orEmpty().ifBlank { location.displayText }
        }
    }

    private fun buildTitleText(kind: String?, title: String): String {
        val kindDisplay = toolKindDisplay(kind)
        val kindTitleLabel = kindLabel(kind)
        return if (title.startsWith("$kindTitleLabel ", ignoreCase = true)) {
            "$kindDisplay ${title.drop(kindTitleLabel.length).trimStart()}"
        } else if (title.equals(kindTitleLabel, ignoreCase = true)) {
            kindDisplay
        } else {
            "$kindDisplay $title"
        }
    }

    private fun navigateTo(
        location: AcpSessionService.ToolCallLocationInfo,
        file: VirtualFile
    ) {
        val descriptor = location.line?.let { line ->
            OpenFileDescriptor(project, file, line.minus(1).coerceAtLeast(0), 0)
        } ?: OpenFileDescriptor(project, file)
        descriptor.navigate(true)
    }

    private fun resolveNavigableFile(pathText: String): VirtualFile? = try {
        val nioPath = Path.of(pathText).let { rawPath ->
            if (!rawPath.isAbsolute) {
                project.guessProjectDir()?.findFileByRelativePath(pathText)?.let { return it }
            }
            if (rawPath.isAbsolute) {
                rawPath.normalize()
            } else {
                val projectBasePath = project.basePath ?: return null
                Path.of(projectBasePath).resolve(rawPath).normalize()
            }
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
    } catch (_: InvalidPathException) {
        null
    }

    private fun rebuildDiffPreviews(diffContents: List<AcpSessionService.ToolCallDiffInfo>) {
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
        val request = ToolCallDiffPreviewFactory.buildRequest(project, diff)
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

    fun updateStatus(status: String, failureSummary: String? = null) {
        statusText.text = status.toDisplayLabel()
        statusIcon.updateStatus(status, failureSummary)
        toolTipText = statusIcon.toolTipText
        revalidate()
        repaint()
    }
}

internal object ToolCallDiffPreviewFactory {
    private val logger = Logger.getInstance(ToolCallDiffPreviewFactory::class.java)

    enum class ContentSource {
        PROTOCOL_OLD_TEXT,
        WORKSPACE_FILE,
        EMPTY_NEW_FILE,
        NEW_TEXT_CONTEXTUAL,
        NEW_TEXT_PLAIN
    }

    data class DiffPreviewContents(
        val before: DiffContent,
        val after: DiffContent,
        val beforeSource: ContentSource,
        val afterSource: ContentSource
    )

    fun buildRequest(
        project: Project,
        diff: AcpSessionService.ToolCallDiffInfo
    ): SimpleDiffRequest {
        val preview = build(project, diff)
        return SimpleDiffRequest(
            diff.path,
            preview.before,
            preview.after,
            MyBundle.message("toolcall.diff.before"),
            MyBundle.message("toolcall.diff.after")
        ).apply {
            putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, SimpleDiffTool.INSTANCE)
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
        }
    }

    fun build(
        project: Project,
        diff: AcpSessionService.ToolCallDiffInfo
    ): DiffPreviewContents {
        val contentFactory = DiffContentFactory.getInstance()
        val context = resolvePathContext(project, diff.path)
        val (afterContent, afterSource) = createAfterContent(contentFactory, project, diff.newText, context)
        val (beforeContent, beforeSource) = if (diff.oldText != null) {
            createProtocolBeforeContent(contentFactory, project, diff.oldText, context)
        } else if (context?.virtualFile != null) {
            logger.info("[ToolCallDiffPreviewFactory] Missing oldText for existing file ${diff.path}; falling back to workspace content")
            createWorkspaceBeforeContent(contentFactory, project, context.virtualFile)
        } else {
            contentFactory.createEmpty() to ContentSource.EMPTY_NEW_FILE
        }
        return DiffPreviewContents(
            before = beforeContent,
            after = afterContent,
            beforeSource = beforeSource,
            afterSource = afterSource
        )
    }

    private fun createProtocolBeforeContent(
        contentFactory: DiffContentFactory,
        project: Project,
        text: String,
        context: PathContext?
    ): Pair<DiffContent, ContentSource> {
        return when {
            context?.virtualFile != null -> contentFactory.create(project, text, context.virtualFile) to ContentSource.PROTOCOL_OLD_TEXT
            context?.filePath != null -> contentFactory.create(project, text, context.filePath) to ContentSource.PROTOCOL_OLD_TEXT
            else -> contentFactory.create(project, text) to ContentSource.PROTOCOL_OLD_TEXT
        }
    }

    private fun createAfterContent(
        contentFactory: DiffContentFactory,
        project: Project,
        text: String,
        context: PathContext?
    ): Pair<DiffContent, ContentSource> {
        context?.virtualFile?.let { return contentFactory.create(project, text, it) to ContentSource.NEW_TEXT_CONTEXTUAL }
        context?.filePath?.let { return contentFactory.create(project, text, it) to ContentSource.NEW_TEXT_CONTEXTUAL }
        return contentFactory.create(project, text) to ContentSource.NEW_TEXT_PLAIN
    }

    private fun createWorkspaceBeforeContent(
        contentFactory: DiffContentFactory,
        project: Project,
        virtualFile: VirtualFile
    ): Pair<DiffContent, ContentSource> {
        return (contentFactory.createDocument(project, virtualFile) ?: contentFactory.create(project, virtualFile)) to
            ContentSource.WORKSPACE_FILE
    }

    private fun resolvePathContext(project: Project, pathText: String): PathContext? {
        val nioPath = parseProjectPath(project, pathText) ?: return null
        val virtualFile = resolveVirtualFile(project, pathText, nioPath)
        if (virtualFile != null) {
            return PathContext(virtualFile = virtualFile, filePath = null)
        }
        return PathContext(virtualFile = null, filePath = LocalFilePath(nioPath.toString(), false))
    }

    private fun resolveVirtualFile(project: Project, pathText: String, nioPath: Path): VirtualFile? {
        if (!Path.of(pathText).isAbsolute) {
            project.guessProjectDir()?.findFileByRelativePath(pathText)?.let { return it }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
    }

    private fun parseProjectPath(project: Project, pathText: String): Path? = try {
        Path.of(pathText).let { rawPath ->
            when {
                rawPath.isAbsolute -> rawPath.normalize()
                project.basePath != null -> Path.of(project.basePath).resolve(rawPath).normalize()
                else -> null
            }
        }
    } catch (_: InvalidPathException) {
        null
    }

    private data class PathContext(
        val virtualFile: VirtualFile?,
        val filePath: FilePath?
    )
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
        val description = rawText
            .let(::helpTooltipDescriptionFor)
            ?: return
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
