package github.ponyhuang.acpplugin.toolwindow.ui.chat

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.InvalidPathException
import java.nio.file.Path

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
        val nioPath = resolveProjectPath(project, pathText) ?: return null
        val virtualFile = resolveProjectVirtualFile(project, pathText)
        if (virtualFile != null) {
            return PathContext(virtualFile = virtualFile, filePath = null)
        }
        return PathContext(virtualFile = null, filePath = LocalFilePath(nioPath.toString(), false))
    }

    private data class PathContext(
        val virtualFile: VirtualFile?,
        val filePath: FilePath?
    )
}

internal fun resolveProjectPath(project: Project, pathText: String): Path? = try {
    Path.of(pathText).let { rawPath ->
        when {
            rawPath.isAbsolute -> rawPath.normalize()
            project.basePath != null -> Path.of(project.basePath).resolve(rawPath).normalize()
            else -> null
        }
    }
} catch (_: java.nio.file.InvalidPathException) {
    null
}

internal fun resolveProjectVirtualFile(
    project: Project,
    pathText: String
): VirtualFile? {
    val resolvedPath = resolveProjectPath(project, pathText) ?: return null
    val rawPath = try {
        Path.of(pathText)
    } catch (_: InvalidPathException) {
        return null
    }
    if (!rawPath.isAbsolute) {
        project.guessProjectDir()?.findFileByRelativePath(pathText)?.let { return it }
    }
    return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
}
