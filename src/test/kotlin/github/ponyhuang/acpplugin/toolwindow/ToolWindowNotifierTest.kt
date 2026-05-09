package github.ponyhuang.acpplugin.toolwindow

import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolWindowNotifierTest : BasePlatformTestCase() {

    fun testBuildLoadedSessionNotificationContentEscapesHtmlCharacters() {
        val notifier = ToolWindowNotifier(project)

        val content = notifier.buildLoadedSessionNotificationContent(
            AcpSessionService.SessionListItem(
                sessionId = "session<&>",
                title = "Read <file> & summarize",
                cwd = project.basePath ?: ".",
                updatedAtMillis = null
            )
        )

        assertEquals(
            "<html>Read &lt;file&gt; &amp; summarize (sessionId: session&lt;&amp;&gt;)</html>",
            content
        )
    }

    fun testBuildLoadedSessionNotificationContentUsesFallbackTitle() {
        val notifier = ToolWindowNotifier(project)

        val content = notifier.buildLoadedSessionNotificationContent(
            AcpSessionService.SessionListItem(
                sessionId = "session-123",
                title = "",
                cwd = project.basePath ?: ".",
                updatedAtMillis = null
            )
        )

        assertEquals(
            "<html>New Session (sessionId: session-123)</html>",
            content
        )
    }
}
