package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class ToolCallRow(
    project: Project,
    toolCall: AcpSessionService.ToolCallInfo
) : JPanel(), Disposable {
    private val modelMapper = ToolCallRowModelMapper(project)
    private val headerPanel = ToolCallHeaderPanel(project)
    private var currentModel: ToolCallRowModel? = null

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)
        chrome.contentPanel.layout = BorderLayout()
        chrome.contentPanel.add(headerPanel, BorderLayout.CENTER)

        update(toolCall)
    }

    fun update(toolCall: AcpSessionService.ToolCallInfo) {
        val nextModel = modelMapper.map(toolCall)
        if (nextModel == currentModel) {
            return
        }
        headerPanel.update(nextModel)
        currentModel = nextModel
        revalidate()
        repaint()
    }

    override fun dispose() = Unit
}

internal class ToolCallHeaderPanel(
    private val project: Project
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val titleLayout = CardLayout()
    private val titleContainer = JPanel(titleLayout).apply {
        isOpaque = false
    }
    private val defaultHeader = DefaultToolCallHeaderView()
    private val navigableHeader = NavigableToolCallHeaderView(project)
    private val diffActionPanel = ToolCallDiffActionPanel(project)
    private val statusLabel = ToolStatusLabel("pending")
    private val trailingPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(diffActionPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.EAST)
    }
    private var currentTitleModel: ToolCallTitleModel? = null
    private var currentStatusModel: ToolCallStatusModel? = null
    private var currentDiffContents: List<AcpSessionService.ToolCallDiffInfo> = emptyList()

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        titleContainer.add(defaultHeader.component, DefaultToolCallHeaderView.CARD_ID)
        titleContainer.add(navigableHeader.component, NavigableToolCallHeaderView.CARD_ID)
        add(titleContainer, BorderLayout.CENTER)
        add(trailingPanel, BorderLayout.EAST)
    }

    fun update(model: ToolCallRowModel) {
        if (model.title != currentTitleModel) {
            applyTitle(model.title)
            currentTitleModel = model.title
        }
        if (model.status != currentStatusModel) {
            statusLabel.updateStatus(model.status.status, model.status.summary)
            currentStatusModel = model.status
        }
        if (model.diffContents != currentDiffContents) {
            diffActionPanel.update(model.diffContents)
            currentDiffContents = model.diffContents
        }
    }

    private fun applyTitle(model: ToolCallTitleModel) {
        when (model) {
            is ToolCallTitleModel.Default -> {
                defaultHeader.update(model)
                titleLayout.show(titleContainer, DefaultToolCallHeaderView.CARD_ID)
            }
            is ToolCallTitleModel.Navigable -> {
                navigableHeader.update(model)
                titleLayout.show(titleContainer, NavigableToolCallHeaderView.CARD_ID)
            }
        }
    }
}

internal class ToolCallDiffActionPanel(
    private val project: Project
) : JPanel(BorderLayout()) {
    private val openDiffLink = ActionLink(MyBundle.message("toolcall.diff.openPreview")) {
        diffContents.firstOrNull()?.let(::openDiffPreview)
    }.apply {
        isVisible = false
        border = JBUI.Borders.emptyRight(8)
    }
    private var diffContents: List<AcpSessionService.ToolCallDiffInfo> = emptyList()

    init {
        isOpaque = false
        add(openDiffLink, BorderLayout.CENTER)
    }

    fun update(diffContents: List<AcpSessionService.ToolCallDiffInfo>) {
        this.diffContents = diffContents
        openDiffLink.isVisible = diffContents.isNotEmpty()
    }

    private fun openDiffPreview(diff: AcpSessionService.ToolCallDiffInfo) {
        val request = ToolCallDiffPreviewFactory.buildRequest(project, diff)
        DiffManager.getInstance().showDiff(project, request, DiffDialogHints.FRAME)
    }
}

internal class ToolCallRowModelMapper(project: Project) {
    private val navigationResolver = ToolCallNavigationResolver(project)

    fun map(toolCall: AcpSessionService.ToolCallInfo): ToolCallRowModel {
        val primaryLocation = toolCall.locations.firstOrNull()
        val navigationTarget = primaryLocation?.takeIf { toolCall.kind == "read" }?.let(navigationResolver::resolve)
        val title = if (navigationTarget != null) {
            ToolCallTitleModel.Navigable(
                icon = toolKindIcon(toolCall.kind),
                labelText = toolKindDisplay(toolCall.kind),
                navigationText = navigationResolver.linkTextFor(navigationTarget),
                navigationTarget = navigationTarget
            )
        } else {
            ToolCallTitleModel.Default(
                icon = toolKindIcon(toolCall.kind),
                text = buildTitleText(toolCall.kind, toolCall.title)
            )
        }
        return ToolCallRowModel(
            title = title,
            status = ToolCallStatusModel(
                status = toolCall.status,
                summary = toolCall.failureDetails ?: toolCall.contentSummary
            ),
            diffContents = toolCall.diffContents
        )
    }

    private fun buildTitleText(kind: String?, title: String): String {
        val kindDisplay = toolKindDisplay(kind)
        val kindTitleLabel = kindLabel(kind)
        return when {
            title.startsWith("$kindTitleLabel ", ignoreCase = true) ->
                "$kindDisplay ${title.drop(kindTitleLabel.length).trimStart()}"
            title.equals(kindTitleLabel, ignoreCase = true) -> kindDisplay
            else -> "$kindDisplay $title"
        }
    }
}

internal class ToolCallNavigationResolver(
    private val project: Project
) {
    fun resolve(location: AcpSessionService.ToolCallLocationInfo): ToolCallNavigationTarget? {
        val file = resolveProjectVirtualFile(project, location.path) ?: return null
        return ToolCallNavigationTarget(location, file)
    }

    fun linkTextFor(target: ToolCallNavigationTarget): String {
        return target.file.name.ifBlank {
            Path.of(target.location.path).fileName?.toString().orEmpty().ifBlank { target.location.displayText }
        }
    }
}

internal object ToolCallNavigator {
    fun navigate(project: Project, target: ToolCallNavigationTarget) {
        val descriptor = target.location.line?.let { line ->
            OpenFileDescriptor(project, target.file, line.minus(1).coerceAtLeast(0), 0)
        } ?: OpenFileDescriptor(project, target.file)
        descriptor.navigate(true)
    }
}

internal data class ToolCallRowModel(
    val title: ToolCallTitleModel,
    val status: ToolCallStatusModel,
    val diffContents: List<AcpSessionService.ToolCallDiffInfo>
)

internal sealed interface ToolCallTitleModel {
    val icon: Icon

    data class Default(
        override val icon: Icon,
        val text: String
    ) : ToolCallTitleModel

    data class Navigable(
        override val icon: Icon,
        val labelText: String,
        val navigationText: String,
        val navigationTarget: ToolCallNavigationTarget
    ) : ToolCallTitleModel
}

internal data class ToolCallStatusModel(
    val status: String,
    val summary: String?
)

internal data class ToolCallNavigationTarget(
    val location: AcpSessionService.ToolCallLocationInfo,
    val file: VirtualFile
)

internal interface ToolCallHeaderView<T : ToolCallTitleModel> {
    val component: JComponent

    fun update(model: T)
}

internal class DefaultToolCallHeaderView : ToolCallHeaderView<ToolCallTitleModel.Default> {
    override val component: JBLabel = JBLabel().apply {
        isAllowAutoWrapping = true
        verticalAlignment = SwingConstants.TOP
        foreground = UIUtil.getLabelForeground()
    }

    override fun update(model: ToolCallTitleModel.Default) {
        component.icon = model.icon
        component.text = model.text
    }

    companion object {
        const val CARD_ID = "default"
    }
}

internal class NavigableToolCallHeaderView(
    private val project: Project
) : ToolCallHeaderView<ToolCallTitleModel.Navigable> {
    private val titleLabel = JBLabel().apply {
        isAllowAutoWrapping = true
        verticalAlignment = SwingConstants.TOP
        foreground = UIUtil.getLabelForeground()
    }
    private val titleLink = ActionLink("") {}.apply {
        border = JBUI.Borders.empty()
        alignmentX = 0f
    }

    override val component: JComponent = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(titleLabel, BorderLayout.CENTER)
        add(titleLink, BorderLayout.EAST)
    }

    override fun update(model: ToolCallTitleModel.Navigable) {
        titleLabel.icon = model.icon
        titleLabel.text = model.labelText
        titleLink.text = model.navigationText
        titleLink.actionListeners.forEach(titleLink::removeActionListener)
        titleLink.addActionListener {
            ToolCallNavigator.navigate(project, model.navigationTarget)
        }
    }

    companion object {
        const val CARD_ID = "navigable"
    }
}
