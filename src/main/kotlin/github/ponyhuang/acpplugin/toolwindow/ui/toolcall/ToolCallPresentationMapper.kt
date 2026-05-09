package github.ponyhuang.acpplugin.toolwindow.ui.toolcall

import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import javax.swing.Icon

internal class ToolCallPresentationMapper(project: Project) {
    private val navigationResolver = ToolCallNavigationResolver(project)

    fun map(toolCall: AcpSessionService.ToolCallInfo): ToolCallRowModel {
        val primaryLocation = toolCall.locations.firstOrNull()
        val navigationTarget = primaryLocation?.takeIf { toolCall.kind == "read" }?.let(navigationResolver::resolve)
        val title = if (navigationTarget != null) {
            ToolCallTitleModel.Navigable(
                navigationText = navigationResolver.linkTextFor(navigationTarget),
                navigationTarget = navigationTarget
            )
        } else {
            ToolCallTitleModel.Default(
                text = toolCall.title
            )
        }
        return ToolCallRowModel(
            kind = ToolCallKindModel(
                icon = toolKindIcon(toolCall.kind),
                text = toolKindDisplay(toolCall.kind)
            ),
            title = title,
            status = ToolCallStatusModel(
                status = toolCall.status,
                summary = toolCall.failureDetails ?: toolCall.contentSummary
            ),
            diffContents = toolCall.diffContents
        )
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
    val kind: ToolCallKindModel,
    val title: ToolCallTitleModel,
    val status: ToolCallStatusModel,
    val diffContents: List<AcpSessionService.ToolCallDiffInfo>
)

internal data class ToolCallKindModel(
    val icon: Icon,
    val text: String
)

internal sealed interface ToolCallTitleModel {
    data class Default(
        val text: String
    ) : ToolCallTitleModel

    data class Navigable(
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
