package github.ponyhuang.acpplugin.toolwindow.ui.toolcall

import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolCallPresentationMapperTest : BasePlatformTestCase() {
    fun testMapsReadableProjectFileToNavigableTitle() {
        myFixture.addFileToProject("src/Foo.kt", "fun main() = Unit")

        val model = ToolCallPresentationMapper(project).map(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-1",
                title = "Read src/Foo.kt",
                kind = "read",
                locations = listOf(
                    AcpSessionService.ToolCallLocationInfo(
                        displayText = "src/Foo.kt",
                        path = "src/Foo.kt",
                        line = 1
                    )
                )
            )
        )

        val title = model.title as ToolCallTitleModel.Navigable
        assertEquals("Foo.kt", title.navigationText)
    }

    fun testMapsStatusSummaryAndDiffContents() {
        val diff = AcpSessionService.ToolCallDiffInfo(
            path = "src/Foo.kt",
            oldText = "old",
            newText = "new"
        )

        val model = ToolCallPresentationMapper(project).map(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-1",
                title = "Edit file",
                kind = "edit",
                status = "failed",
                contentSummary = "fallback",
                failureDetails = "failure",
                diffContents = listOf(diff)
            )
        )

        assertTrue(model.title is ToolCallTitleModel.Default)
        assertEquals("failed", model.status.status)
        assertEquals("failure", model.status.summary)
        assertEquals(listOf(diff), model.diffContents)
    }
}
