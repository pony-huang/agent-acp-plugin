package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.ide.HelpTooltip
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.lang.reflect.Constructor
import java.awt.Component
import java.awt.Container
import java.awt.GridBagLayout
import java.awt.BorderLayout
import java.lang.reflect.Method
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JRadioButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

class AcpConversationPanelTest : BasePlatformTestCase() {
    private val noOpPermissionSubmit: (String, String) -> Unit = { _, _ -> }
    private val noOpPermissionCardCreated: (String, javax.swing.JComponent) -> Unit = { _, _ -> }
    private val noOpCancel: () -> Unit = { }
    private val noOpThoughtToggle: (Boolean) -> Unit = { }

    fun testMessagePanelUsesGridBagLayoutWithCompactHorizontalInset() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
                isAccessible = true
            }.get(panel) as JPanel

            assertTrue(messagePanel.layout is GridBagLayout)
            val borderInsets = messagePanel.border.getBorderInsets(messagePanel)
            assertEquals(JBUI.scale(4), borderInsets.left)
            assertEquals(JBUI.scale(4), borderInsets.right)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testMessageCardsAndPermissionCardsFillConversationWidth() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            panel.setSize(480, 720)

            val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
                isAccessible = true
            }.get(panel) as JPanel

            val messageCard = instantiatePrivatePanel(
                "github.ponyhuang.acpplugin.toolwindow.ui.MessageCardPanel",
                arrayOf(
                    Project::class.java,
                    AcpSessionService.ChatMessage::class.java,
                    Function2::class.java,
                    Function2::class.java,
                    Function0::class.java,
                    Class.forName("github.ponyhuang.acpplugin.toolwindow.ui.MessagePromptState"),
                    java.lang.Boolean.TYPE,
                    Function1::class.java
                ),
                arrayOf<Any?>(
                    project,
                    AcpSessionService.ChatMessage(
                        id = "assistant-fill",
                        role = "assistant",
                        content = "Body",
                        thought = "thinking",
                        toolCalls = emptyList()
                    ),
                    noOpPermissionSubmit,
                    noOpPermissionCardCreated,
                    noOpCancel,
                    null,
                    false,
                    noOpThoughtToggle
                )
            )
            val permissionCard = instantiatePrivatePanel(
                "github.ponyhuang.acpplugin.toolwindow.ui.PermissionRequestCardPanel",
                arrayOf(
                    AcpSessionService.PermissionRequestInfo::class.java,
                    Function1::class.java
                ),
                arrayOf(
                    AcpSessionService.PermissionRequestInfo(
                        requestId = "request-fill",
                        toolCallId = "tool-fill",
                        title = "Permission",
                        options = listOf(
                            AcpSessionService.PermissionOptionInfo(
                                optionId = "allow-once",
                                label = "Allow once",
                                kind = "allow_once"
                            )
                        ),
                        selectedOptionId = null,
                        submitted = false
                    ),
                    { _: String -> }
                )
            )

            messagePanel.add(messageCard, createMessagePanelConstraints(0))
            messagePanel.add(permissionCard, createMessagePanelConstraints(1))
            messagePanel.add(JPanel().apply { isOpaque = false }, createMessagePanelSpacerConstraints(2))

            messagePanel.setSize(420, 600)
            messagePanel.doLayout()

            val expectedWidth = messagePanel.width - messagePanel.insets.left - messagePanel.insets.right
            assertEquals(messagePanel.insets.left, messageCard.x)
            assertEquals(expectedWidth, messageCard.width)
            assertEquals(messagePanel.insets.left, permissionCard.x)
            assertEquals(expectedWidth, permissionCard.width)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testConversationPanelDoesNotEmbedStatusHeaderComponent() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            assertNull(findByClassName(panel, "SessionStatusPanel"))
            assertNull(findByClassName(panel, "ConversationSummaryPanel"))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testMessageCardAndChildrenClampMaximumHeightToPreferredHeight() {
        val message = AcpSessionService.ChatMessage(
            id = "assistant-1",
            role = "assistant",
            content = "Short reply",
            thought = "thinking",
            toolCalls = listOf(
                AcpSessionService.ToolCallInfo(
                    toolCallId = "tool-1",
                    title = "Read file",
                    status = "completed",
                    kind = "read",
                    locations = listOf(locationInfo("src/Main.kt:12", "src/Main.kt", 12)),
                    contentSummary = "done"
                )
            )
        )
        val card = instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.MessageCardPanel",
            arrayOf(
                Project::class.java,
                AcpSessionService.ChatMessage::class.java,
                Function2::class.java,
                Function2::class.java,
                Function0::class.java,
                Class.forName("github.ponyhuang.acpplugin.toolwindow.ui.MessagePromptState"),
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
                project,
                message,
                noOpPermissionSubmit,
                noOpPermissionCardCreated,
                noOpCancel,
                null,
                false,
                noOpThoughtToggle
            )
        )

        card.setSize(480, 900)
        card.doLayout()

        assertEquals(card.preferredSize.height, card.maximumSize.height)

        val thoughtPanel = findByClassName(card, "ThoughtPanel") as javax.swing.JComponent
        val toolRow = findByClassName(card, "ToolCallRow") as javax.swing.JComponent
        val markdownPane = findByClassName(card, "MarkdownPane") as javax.swing.JComponent

        assertEquals(thoughtPanel.preferredSize.height, thoughtPanel.maximumSize.height)
        assertEquals(toolRow.preferredSize.height, toolRow.maximumSize.height)
        assertEquals(markdownPane.preferredSize.height, markdownPane.maximumSize.height)
    }

    fun testAssistantFooterStacksBelowBodyContent() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-stacked",
                role = "assistant",
                content = "A long reply body that should remain fully visible above the completion status.",
                thought = "Intermediate reasoning",
                toolCalls = listOf(
                    AcpSessionService.ToolCallInfo(
                        toolCallId = "tool-stack",
                        title = "Read file",
                        status = "completed",
                        kind = "read",
                        contentSummary = "Done"
                    )
                )
            ),
            messagePromptState("COMPLETED")
        )

        val host = JPanel(BorderLayout()).apply {
            setSize(280, 900)
            add(card, BorderLayout.NORTH)
        }

        host.doLayout()
        card.setSize(240, card.preferredSize.height)
        layoutRecursively(host)

        val toolRow = findByClassName(card, "ToolCallRow") as javax.swing.JComponent
        val markdownPane = findAllByType(card, javax.swing.JComponent::class.java)
            .last { it.javaClass.simpleName == "MarkdownPane" }
        val footer = findByClassName(card, "MessagePromptFooter") as javax.swing.JComponent

        assertTrue(toolRow.y + toolRow.height <= markdownPane.y)
        assertTrue(markdownPane.y + markdownPane.height <= footer.y)
    }

    fun testEditToolCallMessageKeepsAssistantContentMarkdownVisible() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-edit-content-hidden",
                role = "assistant",
                content = "Edited file contents summary",
                toolCalls = listOf(
                    AcpSessionService.ToolCallInfo(
                        toolCallId = "tool-edit-content-hidden",
                        title = "Edit file",
                        status = "completed",
                        kind = "edit"
                    )
                )
            ),
            promptState = null
        )

        val toolRow = findByClassName(card, "ToolCallRow")
        val markdownPane = findByClassName(card, "MarkdownPane")

        assertNotNull(toolRow)
        assertNotNull(markdownPane)
    }

    fun testSearchToolCallMessageKeepsAssistantContentMarkdownVisible() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-search-content-visible",
                role = "assistant",
                content = "Found matching files",
                toolCalls = listOf(
                    AcpSessionService.ToolCallInfo(
                        toolCallId = "tool-search-content-visible",
                        title = "Search files",
                        status = "completed",
                        kind = "search"
                    )
                )
            ),
            promptState = null
        )

        val toolRow = findByClassName(card, "ToolCallRow")
        val markdownPane = findByClassName(card, "MarkdownPane")

        assertNotNull(toolRow)
        assertNotNull(markdownPane)
    }

    fun testAssistantLongMarkdownKeepsMultipleVisibleRowsAboveFooter() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-long-markdown",
                role = "assistant",
                content = "This is a deliberately long assistant response line that should wrap across multiple rows when the card is narrow. " +
                    "This is a deliberately long assistant response line that should wrap across multiple rows when the card is narrow. " +
                    "This is a deliberately long assistant response line that should wrap across multiple rows when the card is narrow."
            ),
            messagePromptState("COMPLETED")
        )

        val host = JPanel(BorderLayout()).apply {
            setSize(280, 900)
            add(card, BorderLayout.NORTH)
        }

        host.doLayout()
        card.setSize(240, card.preferredSize.height)
        layoutRecursively(host)

        val markdownPane = findByClassName(card, "MarkdownPane") as javax.swing.JComponent
        val footer = findByClassName(card, "MessagePromptFooter") as javax.swing.JComponent

        assertTrue(
            "markdown=${markdownPane.bounds} footer=${footer.bounds} card=${card.bounds} pref=${card.preferredSize}",
            markdownPane.height > JBUI.scale(32)
        )
        assertTrue(
            "markdown=${markdownPane.bounds} footer=${footer.bounds} card=${card.bounds} pref=${card.preferredSize}",
            markdownPane.y + markdownPane.height <= footer.y
        )
    }

    fun testConversationIncrementalMarkdownUpdateRelayoutsRowHeight() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val host = JPanel(BorderLayout()).apply {
                setSize(320, 900)
                add(panel, BorderLayout.CENTER)
            }
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-streaming",
                role = "assistant",
                content = "Short reply"
            )
            renderConversation(panel, listOf(initialMessage))
            layoutRecursively(host)

            val initialRow = mountedMessageRowComponent(panel, 0)
            val initialHeight = initialRow.height
            val initialMarkdownHeight =
                (findByClassName(initialRow, "MarkdownPane") as javax.swing.JComponent).height

            val updatedMessage = initialMessage.copy(
                content = "This is a deliberately longer assistant response that should wrap across multiple lines after an incremental update. " +
                    "This is a deliberately longer assistant response that should wrap across multiple lines after an incremental update."
            )
            renderConversation(panel, listOf(updatedMessage))
            layoutRecursively(host)

            val updatedRow = mountedMessageRowComponent(panel, 0)
            val markdownPane = findByClassName(updatedRow, "MarkdownPane") as javax.swing.JComponent

            assertSame(initialRow, updatedRow)
            assertTrue("row=${updatedRow.bounds} pref=${updatedRow.preferredSize}", updatedRow.height > initialHeight)
            assertTrue("markdown=${markdownPane.bounds}", markdownPane.height > initialMarkdownHeight)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testThoughtPanelLimitsExpandedHeightWithInternalScroll() {
        val longThought = List(40) { index ->
            "Thought line ${index + 1} should keep the expanded panel readable without growing the full message card indefinitely."
        }.joinToString("\n\n")
        val thoughtPanel = instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.ThoughtPanel",
            arrayOf(String::class.java, java.lang.Boolean.TYPE, Function1::class.java),
            arrayOf(longThought, true, noOpThoughtToggle)
        )

        val host = JPanel(BorderLayout()).apply {
            setSize(360, 700)
            add(thoughtPanel, BorderLayout.NORTH)
        }

        layoutRecursively(host)

        val scrollPane = thoughtPanel.javaClass.getDeclaredField("scrollPane").apply {
            isAccessible = true
        }.get(thoughtPanel) as JBScrollPane
        val markdownPane = findByClassName(thoughtPanel, "MarkdownPane") as javax.swing.JComponent

        assertTrue(scrollPane.isVisible)
        assertTrue(scrollPane.height <= JBUI.scale(240))
        assertTrue(markdownPane.preferredSize.height > scrollPane.height)
        assertEquals(JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scrollPane.horizontalScrollBarPolicy)
    }

    fun testThoughtToggleRelayoutsMountedConversationRowAndViewport() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val longThought = List(24) { index ->
                "Thought line ${index + 1} should force a visible expanded region inside the conversation row."
            }.joinToString("\n\n")
            val host = JPanel(BorderLayout()).apply {
                setSize(420, 720)
                add(panel, BorderLayout.CENTER)
            }
            val message = AcpSessionService.ChatMessage(
                id = "assistant-thought-toggle-layout",
                role = "assistant",
                content = "Answer",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought(longThought),
                    AcpSessionService.MessageEntry.Content("Answer")
                )
            )
            renderConversation(panel, listOf(message))
            layoutRecursively(host)

            val row = messageRowComponent(panel, "assistant-thought-toggle-layout")
            val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
                isAccessible = true
            }.get(panel) as JPanel
            val messageScrollPane = panel.javaClass.getDeclaredField("messageScrollPane").apply {
                isAccessible = true
            }.get(panel) as JBScrollPane
            val thoughtPanel = findByClassName(row, "ThoughtPanel") as javax.swing.JComponent
            val contentPanel = thoughtPanel.javaClass.getDeclaredField("contentPanel").apply {
                isAccessible = true
            }.get(thoughtPanel) as JPanel
            val toggle = findAllByType(thoughtPanel, ActionLink::class.java).first { it.text == "Show Thinking" }
            val collapsedHeight = row.height
            val collapsedPreferredHeight = messagePanel.preferredSize.height

            toggle.doClick()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            assertTrue(contentPanel.isVisible)
            assertTrue("row=${row.bounds} pref=${row.preferredSize}", row.height > collapsedHeight)
            assertTrue(
                "panel=${messagePanel.bounds} pref=${messagePanel.preferredSize}",
                messagePanel.preferredSize.height > collapsedPreferredHeight
            )
            assertTrue(
                "viewSize=${messageScrollPane.viewport.viewSize} row=${row.bounds}",
                messageScrollPane.viewport.viewSize.height >= row.y + row.height
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testThoughtToggleTracksViewportWidthForMarkdownContent() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val markdownThought = buildString {
                appendLine("This is a deliberately long thought paragraph that should wrap to the available viewport width after expansion.")
                appendLine()
                repeat(6) { index ->
                    appendLine("- Bullet ${index + 1} contains a long description that should wrap in a narrow conversation column and contribute to the measured preferred height.")
                }
                appendLine()
                appendLine("```")
                repeat(8) { index ->
                    appendLine("val line$index = \"This code block line should still be measured against the viewport width after expansion\"")
                }
                appendLine("```")
            }.trim()
            val host = JPanel(BorderLayout()).apply {
                setSize(360, 720)
                add(panel, BorderLayout.CENTER)
            }
            val message = AcpSessionService.ChatMessage(
                id = "assistant-thought-markdown-width",
                role = "assistant",
                content = "Answer",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought(markdownThought),
                    AcpSessionService.MessageEntry.Content("Answer")
                )
            )
            renderConversation(panel, listOf(message))
            layoutRecursively(host)

            val row = messageRowComponent(panel, "assistant-thought-markdown-width")
            val thoughtPanel = findByClassName(row, "ThoughtPanel") as javax.swing.JComponent
            val scrollPane = thoughtPanel.javaClass.getDeclaredField("scrollPane").apply {
                isAccessible = true
            }.get(thoughtPanel) as JBScrollPane
            val markdownPane = findByClassName(thoughtPanel, "MarkdownPane") as javax.swing.JComponent
            val toggle = findAllByType(thoughtPanel, ActionLink::class.java).first { it.text == "Show Thinking" }

            toggle.doClick()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            assertTrue(scrollPane.isVisible)
            assertTrue(
                "markdown=${markdownPane.bounds} viewport=${scrollPane.viewport.bounds}",
                markdownPane.width <= scrollPane.viewport.width
            )
            assertTrue(
                "preferred=${markdownPane.preferredSize} scroll=${scrollPane.bounds}",
                markdownPane.preferredSize.height > JBUI.scale(240)
            )
            assertTrue(
                "viewSize=${scrollPane.viewport.viewSize} viewport=${scrollPane.viewport.bounds}",
                scrollPane.viewport.viewSize.width <= scrollPane.viewport.width
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testExpandedThoughtRelayoutsWhenConversationScrollbarAppears() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val widthSensitiveThought = buildString {
                repeat(10) { index ->
                    appendLine("Paragraph ${index + 1} has enough wrapped content to noticeably change height when the available conversation width shrinks.")
                    appendLine()
                }
            }.trim()
            val host = JPanel(BorderLayout()).apply {
                setSize(360, 420)
                add(panel, BorderLayout.CENTER)
            }
            val thoughtMessage = AcpSessionService.ChatMessage(
                id = "assistant-width-shrink",
                role = "assistant",
                content = "Answer",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought(widthSensitiveThought),
                    AcpSessionService.MessageEntry.Content("Answer")
                )
            )
            renderConversation(panel, listOf(thoughtMessage))
            layoutRecursively(host)

            val thoughtRow = messageRowComponent(panel, "assistant-width-shrink")
            val thoughtPanel = findByClassName(thoughtRow, "ThoughtPanel") as javax.swing.JComponent
            val scrollPane = thoughtPanel.javaClass.getDeclaredField("scrollPane").apply {
                isAccessible = true
            }.get(thoughtPanel) as JBScrollPane
            val markdownPane = findByClassName(thoughtPanel, "MarkdownPane") as javax.swing.JComponent
            val messageScrollPane = panel.javaClass.getDeclaredField("messageScrollPane").apply {
                isAccessible = true
            }.get(panel) as JBScrollPane
            val toggle = findAllByType(thoughtPanel, ActionLink::class.java).first { it.text == "Show Thinking" }

            toggle.doClick()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            assertFalse(messageScrollPane.verticalScrollBar.isVisible)
            val widthBeforeScrollbar = markdownPane.width
            val viewportWidthBeforeScrollbar = messageScrollPane.viewport.width
            val preferredHeightBeforeScrollbar = markdownPane.preferredSize.height

            val fillerMessages = List(6) { index ->
                AcpSessionService.ChatMessage(
                    id = "assistant-filler-$index",
                    role = "assistant",
                    content = "Filler response $index\n\n" + "Additional content ".repeat(24),
                    entries = listOf(
                        AcpSessionService.MessageEntry.ToolCall(
                            AcpSessionService.ToolCallInfo(
                                toolCallId = "tool-filler-$index",
                                title = "Read workspace file $index",
                                status = "completed",
                                kind = "read",
                                contentSummary = "Done"
                            )
                        ),
                        AcpSessionService.MessageEntry.Content("Filler response $index\n\n" + "Additional content ".repeat(24))
                    )
                )
            }
            renderConversation(panel, listOf(thoughtMessage) + fillerMessages)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            val thoughtRowAfter = mountedMessageRows(panel).first {
                findByClassName(it, "ThoughtPanel") != null
            }
            val thoughtPanelAfter = findByClassName(thoughtRowAfter, "ThoughtPanel") as javax.swing.JComponent
            val markdownPaneAfter = findByClassName(thoughtPanelAfter, "MarkdownPane") as javax.swing.JComponent

            assertTrue(messageScrollPane.verticalScrollBar.isVisible)
            assertTrue(
                "beforeMarkdownWidth=$widthBeforeScrollbar afterMarkdownWidth=${markdownPaneAfter.width} beforeViewportWidth=$viewportWidthBeforeScrollbar afterViewportWidth=${messageScrollPane.viewport.width}",
                markdownPaneAfter.width <= messageScrollPane.viewport.width
            )
            assertTrue(
                "beforeHeight=$preferredHeightBeforeScrollbar afterHeight=${markdownPaneAfter.preferredSize.height} markdown=${markdownPaneAfter.bounds}",
                markdownPaneAfter.preferredSize.height >= preferredHeightBeforeScrollbar
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testMarkdownContentRewrapsWhenHostWidthShrinks() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val longToken = buildString {
                repeat(12) { append("workspace_path_segment_0123456789/") }
            }
            val shrinkingThought = buildString {
                appendLine("Paragraph content should continue wrapping after the tool window becomes narrower.")
                appendLine()
                appendLine("```")
                appendLine("val veryLongCommand = \"$longToken\"")
                appendLine("```")
            }.trim()
            val host = JPanel(BorderLayout()).apply {
                setSize(460, 520)
                add(panel, BorderLayout.CENTER)
            }
            val message = AcpSessionService.ChatMessage(
                id = "assistant-width-shrink-wrap",
                role = "assistant",
                content = "Body with $longToken",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought(shrinkingThought),
                    AcpSessionService.MessageEntry.ToolCall(
                        AcpSessionService.ToolCallInfo(
                            toolCallId = "tool-width-shrink-wrap",
                            title = "Read $longToken",
                            status = "completed",
                            kind = "read",
                            contentSummary = longToken
                        )
                    ),
                    AcpSessionService.MessageEntry.Content("Body with $longToken")
                )
            )
            renderConversation(panel, listOf(message))
            layoutRecursively(host)

            val row = messageRowComponent(panel, "assistant-width-shrink-wrap")
            val thoughtPanel = findByClassName(row, "ThoughtPanel") as javax.swing.JComponent
            val toggle = findAllByType(thoughtPanel, ActionLink::class.java).first { it.text == "Show Thinking" }
            val messageScrollPane = panel.javaClass.getDeclaredField("messageScrollPane").apply {
                isAccessible = true
            }.get(panel) as JBScrollPane

            toggle.doClick()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            val rowHeightAtWideWidth = row.height
            val viewportWidthAtWideWidth = messageScrollPane.viewport.width
            val thoughtMarkdownAtWideWidth =
                findAllByType(row, javax.swing.JEditorPane::class.java).first { it.text.contains("veryLongCommand") }
            val contentMarkdownAtWideWidth =
                findAllByType(row, javax.swing.JEditorPane::class.java).first { it.text.contains("Body with") }
            val thoughtPreferredHeightAtWideWidth = thoughtMarkdownAtWideWidth.preferredSize.height
            val contentPreferredHeightAtWideWidth = contentMarkdownAtWideWidth.preferredSize.height

            host.setSize(280, 520)
            host.doLayout()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            val shrunkRow = messageRowComponent(panel, "assistant-width-shrink-wrap")
            val thoughtMarkdownAfterShrink =
                findAllByType(shrunkRow, javax.swing.JEditorPane::class.java).first { it.text.contains("veryLongCommand") }
            val contentMarkdownAfterShrink =
                findAllByType(shrunkRow, javax.swing.JEditorPane::class.java).first { it.text.contains("Body with") }

            assertTrue(messageScrollPane.viewport.width < viewportWidthAtWideWidth)
            assertTrue(
                "thoughtWidth=${thoughtMarkdownAfterShrink.width} viewport=${messageScrollPane.viewport.width}",
                thoughtMarkdownAfterShrink.width <= messageScrollPane.viewport.width
            )
            assertTrue(
                "contentWidth=${contentMarkdownAfterShrink.width} viewport=${messageScrollPane.viewport.width}",
                contentMarkdownAfterShrink.width <= messageScrollPane.viewport.width
            )
            assertTrue(
                "wideThoughtPref=$thoughtPreferredHeightAtWideWidth shrunkThoughtPref=${thoughtMarkdownAfterShrink.preferredSize.height}",
                thoughtMarkdownAfterShrink.preferredSize.height >= thoughtPreferredHeightAtWideWidth
            )
            assertTrue(
                "wideContentPref=$contentPreferredHeightAtWideWidth shrunkContentPref=${contentMarkdownAfterShrink.preferredSize.height}",
                contentMarkdownAfterShrink.preferredSize.height >= contentPreferredHeightAtWideWidth
            )
            assertTrue(
                "wideRow=$rowHeightAtWideWidth shrunkRow=${shrunkRow.height}",
                shrunkRow.height >= rowHeightAtWideWidth
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testToolCallRowRewrapsWhenHostWidthShrinks() {
        val longToken = buildString {
            repeat(12) { append("workspace_path_segment_0123456789/") }
        }
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-wrap-shrink",
                title = "Execute command with very long argument $longToken",
                status = "completed",
                kind = "execute"
            )
        )

        val host = JPanel(BorderLayout()).apply {
            setSize(460, 320)
            add(row, BorderLayout.NORTH)
        }

        layoutRecursively(host)

        val titleLabel = findAllByType(row, JBLabel::class.java)
            .first { it.text.contains("Execute command with very long argument") }
        val wideHeight = row.height
        val widePreferredHeight = titleLabel.preferredSize.height

        host.setSize(260, 320)
        host.doLayout()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        layoutRecursively(host)

        assertTrue("labelWidth=${titleLabel.width} rowWidth=${row.width}", titleLabel.width <= row.width)
        assertTrue(
            "wideLabelHeight=$widePreferredHeight shrunkLabelHeight=${titleLabel.preferredSize.height}",
            titleLabel.preferredSize.height >= widePreferredHeight
        )
        assertTrue("wideRow=$wideHeight shrunkRow=${row.height}", row.height >= wideHeight)
    }

    fun testConversationRenderReusesThoughtPanelForThoughtTextGrowth() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-thought-grow",
                role = "assistant",
                content = "Answer",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought("Short thought"),
                    AcpSessionService.MessageEntry.Content("Answer")
                )
            )
            renderConversation(panel, listOf(initialMessage))

            val firstRow = messageRowComponent(panel, "assistant-thought-grow")
            val firstThoughtPanel = findByClassName(firstRow, "ThoughtPanel")

            val updatedMessage = initialMessage.copy(
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought("Short thought with additional streamed detail that should reuse the same panel instance."),
                    AcpSessionService.MessageEntry.Content("Answer")
                )
            )
            renderConversation(panel, listOf(updatedMessage))

            val secondRow = messageRowComponent(panel, "assistant-thought-grow")
            val secondThoughtPanel = findByClassName(secondRow, "ThoughtPanel")

            assertSame(firstRow, secondRow)
            assertSame(firstThoughtPanel, secondThoughtPanel)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testConversationRenderReusesToolCallRowForStatusUpdate() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-tool-stable",
                role = "assistant",
                content = "Working",
                entries = listOf(
                    AcpSessionService.MessageEntry.ToolCall(
                        AcpSessionService.ToolCallInfo(
                            toolCallId = "tool-stable",
                            title = "Search workspace",
                            status = "in_progress",
                            kind = "search",
                            contentSummary = "Searching"
                        )
                    ),
                    AcpSessionService.MessageEntry.Content("Working")
                )
            )
            renderConversation(panel, listOf(initialMessage))

            val firstRow = messageRowComponent(panel, "assistant-tool-stable")
            val firstToolCallRow = findByClassName(firstRow, "ToolCallRow")

            val updatedMessage = initialMessage.copy(
                content = "Done",
                entries = listOf(
                    AcpSessionService.MessageEntry.ToolCall(
                        AcpSessionService.ToolCallInfo(
                            toolCallId = "tool-stable",
                            title = "Search workspace",
                            status = "completed",
                            kind = "search",
                            contentSummary = "Done"
                        )
                    ),
                    AcpSessionService.MessageEntry.Content("Done")
                )
            )
            renderConversation(panel, listOf(updatedMessage))

            val secondRow = messageRowComponent(panel, "assistant-tool-stable")
            val secondToolCallRow = findByClassName(secondRow, "ToolCallRow")

            assertSame(firstRow, secondRow)
            assertSame(firstToolCallRow, secondToolCallRow)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testUserMessageContentRemainsVisibleAfterRender() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val host = JPanel(BorderLayout()).apply {
                setSize(320, 400)
                add(panel, BorderLayout.CENTER)
            }
            val userMessage = AcpSessionService.ChatMessage(
                id = "user-visible",
                role = "user",
                content = "User prompt content should stay visible."
            )

            renderConversation(panel, listOf(userMessage))
            layoutRecursively(host)

            val row = mountedMessageRowComponent(panel, 0)
            val markdownPane = findByClassName(row, "MarkdownPane") as javax.swing.JEditorPane

            assertTrue("row=${row.bounds} pref=${row.preferredSize}", row.height > 0)
            assertTrue("markdown=${markdownPane.bounds}", markdownPane.height > 0)
            assertTrue(markdownPane.text.contains("User prompt content should stay visible."))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testPermissionRequestCardRendersOptionsAndSubmitState() {
        val request = AcpSessionService.PermissionRequestInfo(
            requestId = "request-1",
            toolCallId = "tool-1",
            title = "Run command",
            options = listOf(
                AcpSessionService.PermissionOptionInfo(
                    optionId = "allow-once",
                    label = "Allow once",
                    kind = "allow_once"
                ),
                AcpSessionService.PermissionOptionInfo(
                    optionId = "reject-once",
                    label = "Reject once",
                    kind = "reject_once"
                )
            ),
            selectedOptionId = "allow-once",
            submitted = false
        )

        val card = instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.PermissionRequestCardPanel",
            arrayOf(
                AcpSessionService.PermissionRequestInfo::class.java,
                Function1::class.java
            ),
            arrayOf(
                request,
                { _: String -> }
            )
        )

        val radios = findAllByType(card, JRadioButton::class.java)
        val buttons = findAllByType(card, JButton::class.java)

        assertEquals(2, radios.size)
        assertTrue(radios.first().isSelected)
        assertEquals("Submit", buttons.single().text)
        assertTrue(buttons.single().isEnabled)
    }

    fun testToolCallRowUsesKindIconAndStaticStatusIcon() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-1",
                title = "Read file",
                status = "completed",
                kind = "read"
            )
        )

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Read file" }
        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel

        assertNotNull(titleLabel)
        assertEquals(AllIcons.Actions.MenuOpen, titleLabel!!.icon)
        assertEquals(AllIcons.General.InspectionsOK, statusIcon.icon)
        assertUsesTemplateChrome(row)
    }

    fun testToolCallRowUsesAnimatedRunningStatusIcon() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-2",
                title = "Search workspace",
                status = "in_progress",
                kind = "search"
            )
        )

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Search workspace" }
        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel
        val animationTimer = statusIcon.javaClass.getDeclaredField("animationTimer").apply {
            isAccessible = true
        }

        assertNotNull(titleLabel)
        assertEquals(AllIcons.Actions.Search, titleLabel!!.icon)
        assertEquals(AllIcons.Process.Step_1, statusIcon.icon)
        assertNotNull(animationTimer.get(statusIcon))
    }

    fun testToolCallRowUsesCancelledStatusIconAndLabel() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-2-cancelled",
                title = "Search workspace",
                status = "cancelled",
                kind = "search"
            )
        )

        val statusText = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Cancelled" }
        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel

        assertNotNull(statusText)
        assertEquals(AllIcons.Actions.Cancel, statusIcon.icon)
    }

    fun testFailedToolCallRowInstallsHelpTooltipOnStatusIcon() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-failed-tooltip",
                title = "Run command",
                status = "failed",
                kind = "execute",
                failureDetails = "Command failed\nExit code 1"
            )
        )

        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel
        val tooltip = HelpTooltip.getTooltipFor(statusIcon)
        val descriptionField = tooltip!!::class.java.getDeclaredField("description").apply {
            isAccessible = true
        }

        assertEquals(AllIcons.General.Error, statusIcon.icon)
        assertEquals("Command failed<br/>Exit code 1", descriptionField.get(tooltip))
        assertEquals("<html>Command failed<br/>Exit code 1</html>", statusIcon.toolTipText)
    }

    fun testFailedToolCallRowRemovesHelpTooltipAfterStatusRecovery() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-failed-tooltip-clear",
                title = "Run command",
                status = "failed",
                kind = "execute",
                failureDetails = "Command failed"
            )
        )

        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel
        assertNotNull(HelpTooltip.getTooltipFor(statusIcon))

        row.javaClass.getMethod("update", AcpSessionService.ToolCallInfo::class.java).invoke(
            row,
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-failed-tooltip-clear",
                title = "Run command",
                status = "completed",
                kind = "execute",
                contentSummary = "Recovered"
            )
        )

        assertNull(HelpTooltip.getTooltipFor(statusIcon))
        assertEquals(AllIcons.General.InspectionsOK, statusIcon.icon)
    }

    fun testFailedToolCallRowSkipsHelpTooltipWithoutFailureSummary() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-failed-no-summary",
                title = "Run command",
                status = "failed",
                kind = "execute"
            )
        )

        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel

        assertEquals(AllIcons.General.Error, statusIcon.icon)
        assertNull(HelpTooltip.getTooltipFor(statusIcon))
    }

    fun testFailedToolCallRowFallsBackToContentSummaryForTooltip() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-failed-content-summary",
                title = "Run command",
                status = "failed",
                kind = "execute",
                contentSummary = "Fallback failure message"
            )
        )

        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel

        assertNotNull(HelpTooltip.getTooltipFor(statusIcon))
        assertEquals("<html>Fallback failure message</html>", statusIcon.toolTipText)
    }

    fun testReadToolCallRowUsesActionLinkForExistingFileAndHidesSummary() {
        myFixture.addFileToProject("src/Main.kt", "class Main")
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-read-link",
                title = "Read file",
                status = "completed",
                kind = "read",
                locations = listOf(locationInfo("src/Main.kt:12", "src/Main.kt", 12)),
                contentSummary = "This content should stay hidden"
            )
        )

        val titlePrefix = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Read" }
        val mainLinks = findAllByType(row, ActionLink::class.java).filter { it.text == "Main.kt" }
        val hiddenSummary = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "This content should stay hidden" }

        assertNotNull(titlePrefix)
        assertEquals(AllIcons.Actions.MenuOpen, titlePrefix!!.icon)
        assertEquals(1, mainLinks.size)
        assertNull(hiddenSummary)
    }

    fun testToolCallRowModelMapperBuildsNavigationTitleForReadableFile() {
        myFixture.addFileToProject("src/Main.kt", "class Main")
        val model = ToolCallRowModelMapper(project).map(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-read-model",
                title = "Read file",
                status = "completed",
                kind = "read",
                locations = listOf(locationInfo("src/Main.kt:12", "src/Main.kt", 12)),
                contentSummary = "This content should stay hidden"
            )
        )

        val title = model.title as ToolCallTitleModel.Navigable
        assertEquals("Read", title.labelText)
        assertEquals("Main.kt", title.navigationText)
        assertNotNull(title.navigationTarget)
        assertEquals("completed", model.status.status)
        assertEquals("This content should stay hidden", model.status.summary)
    }

    fun testReadToolCallRowFallsBackToLabelWhenFileDoesNotExist() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-read-missing",
                title = "Read file",
                status = "completed",
                kind = "read",
                locations = listOf(locationInfo("missing/File.kt:3", "missing/File.kt", 3))
            )
        )

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Read file" }
        val locationLink = findAllByType(row, ActionLink::class.java).firstOrNull { it.text == "missing/File.kt:3" }
        val locationLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "missing/File.kt:3" }

        assertNotNull(titleLabel)
        assertEquals(AllIcons.Actions.MenuOpen, titleLabel!!.icon)
        assertNull(locationLink)
        assertNull(locationLabel)
    }

    fun testToolCallNavigationResolverReturnsNullForMissingFile() {
        val target = ToolCallNavigationResolver(project).resolve(
            locationInfo("missing/File.kt:3", "missing/File.kt", 3)
        )

        assertNull(target)
    }

    fun testNonRepeatedToolCallTitleRemainsIntact() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-edit-title",
                title = "Apply patch",
                status = "completed",
                kind = "edit"
            )
        )

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Edit Apply patch" }

        assertNotNull(titleLabel)
        assertEquals(AllIcons.Actions.Edit, titleLabel!!.icon)
    }

    fun testToolCallRowShowsDiffActionWhenDiffsExist() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-diff-preview",
                title = "Apply patch",
                status = "completed",
                kind = "edit",
                locations = listOf(locationInfo("src/Main.kt:12", "src/Main.kt", 12)),
                diffContents = listOf(
                    AcpSessionService.ToolCallDiffInfo(
                        path = "src/Main.kt",
                        oldText = "old text",
                        newText = "new text"
                    )
                )
            )
        )

        val openDiffLinks = findAllByType(row, ActionLink::class.java)
            .filter { it.text == MyBundle.message("toolcall.diff.openPreview") && it.isVisible }

        assertEquals(1, openDiffLinks.size)
    }

    fun testToolCallRowOmitsLegacyHiddenBodyPanels() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-hidden-body",
                title = "Search workspace",
                status = "completed",
                kind = "search",
                locations = listOf(locationInfo("src/Main.kt:12", "src/Main.kt", 12)),
                diffContents = listOf(
                    AcpSessionService.ToolCallDiffInfo(
                        path = "src/Main.kt",
                        oldText = "old text",
                        newText = "new text"
                    )
                )
            )
        )

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "Search workspace" }

        assertNotNull(titleLabel)
        assertEquals(AllIcons.Actions.Search, titleLabel!!.icon)
        assertFalse(row.javaClass.declaredFields.any { it.name == "detailsPanel" || it.name == "diffContainer" })
    }

    fun testToolCallRowModelMapperPrefersFailureDetailsOverContentSummary() {
        val model = ToolCallRowModelMapper(project).map(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-failure-priority",
                title = "Run command",
                status = "failed",
                kind = "execute",
                failureDetails = "Failure details",
                contentSummary = "Fallback summary"
            )
        )

        assertTrue(model.title is ToolCallTitleModel.Default)
        assertEquals("Failure details", model.status.summary)
    }

    fun testDiffPreviewFactoryUsesDocumentContentForBothSidesWhenBaselineExists() {
        val path = "src/Main.kt"
        myFixture.addFileToProject(path, "class Main")
        val preview = ToolCallDiffPreviewFactory.build(
            project,
            AcpSessionService.ToolCallDiffInfo(
                path = path,
                oldText = "old text",
                newText = "new text"
            )
        )

        assertTrue(preview.before is DocumentContent)
        assertTrue(preview.after is DocumentContent)
        assertEquals("old text", (preview.before as DocumentContent).document.text)
        assertEquals("new text", (preview.after as DocumentContent).document.text)
        assertEquals(ToolCallDiffPreviewFactory.ContentSource.PROTOCOL_OLD_TEXT, preview.beforeSource)
        assertEquals(ToolCallDiffPreviewFactory.ContentSource.NEW_TEXT_CONTEXTUAL, preview.afterSource)
    }

    fun testDiffPreviewFactoryFallsBackToWorkspaceFileWhenBaselineIsMissing() {
        val path = "src/NewFile.kt"
        myFixture.addFileToProject(path, "class NewFile")
        val preview = ToolCallDiffPreviewFactory.build(
            project,
            AcpSessionService.ToolCallDiffInfo(
                path = path,
                oldText = null,
                newText = "new text"
            )
        )

        assertTrue(preview.before is DocumentContent)
        assertTrue(preview.after is DocumentContent)
        assertEquals("class NewFile", (preview.before as DocumentContent).document.text)
        assertEquals("new text", (preview.after as DocumentContent).document.text)
        assertEquals(ToolCallDiffPreviewFactory.ContentSource.WORKSPACE_FILE, preview.beforeSource)
        assertEquals(ToolCallDiffPreviewFactory.ContentSource.NEW_TEXT_CONTEXTUAL, preview.afterSource)
    }

    fun testDiffPreviewFactoryUsesEmptyBeforeSideForTrueNewFile() {
        val preview = ToolCallDiffPreviewFactory.build(
            project,
            AcpSessionService.ToolCallDiffInfo(
                path = "src/BrandNewFile.kt",
                oldText = null,
                newText = "new text"
            )
        )

        assertTrue(preview.before is EmptyContent)
        assertTrue(preview.after is DocumentContent)
        assertEquals("new text", (preview.after as DocumentContent).document.text)
        assertEquals(ToolCallDiffPreviewFactory.ContentSource.EMPTY_NEW_FILE, preview.beforeSource)
        assertEquals(ToolCallDiffPreviewFactory.ContentSource.NEW_TEXT_CONTEXTUAL, preview.afterSource)
    }

    fun testDiffPreviewRequestForcesSideBySideSimpleDiffTool() {
        val request = ToolCallDiffPreviewFactory.buildRequest(
            project,
            AcpSessionService.ToolCallDiffInfo(
                path = "src/Main.kt",
                oldText = "old text",
                newText = "new text"
            )
        )

        assertSame(SimpleDiffTool.INSTANCE, request.getUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL))
        val readOnlyContents = request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)!!
        assertEquals(2, readOnlyContents.size)
        assertTrue(readOnlyContents[0])
        assertTrue(readOnlyContents[1])
    }

    fun testVisualPanelsUseTemplateChromeAndNestedPanels() {
        val message = AcpSessionService.ChatMessage(
            id = "assistant-2",
            role = "assistant",
            content = "Body",
            thought = "thinking",
            toolCalls = listOf(
                AcpSessionService.ToolCallInfo(
                    toolCallId = "tool-3",
                    title = "Fetch docs",
                    status = "pending",
                    kind = "fetch"
                )
            )
        )

        val messageCard = instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.MessageCardPanel",
            arrayOf(
                Project::class.java,
                AcpSessionService.ChatMessage::class.java,
                Function2::class.java,
                Function2::class.java,
                Function0::class.java,
                Class.forName("github.ponyhuang.acpplugin.toolwindow.ui.MessagePromptState"),
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
                project,
                message,
                noOpPermissionSubmit,
                noOpPermissionCardCreated,
                noOpCancel,
                null,
                false,
                noOpThoughtToggle
            )
        )
        val thoughtPanel = findByClassName(messageCard, "ThoughtPanel") as javax.swing.JComponent
        val toolRow = findByClassName(messageCard, "ToolCallRow") as javax.swing.JComponent
        val permissionCard = instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.PermissionRequestCardPanel",
            arrayOf(
                AcpSessionService.PermissionRequestInfo::class.java,
                Function1::class.java
            ),
            arrayOf(
                AcpSessionService.PermissionRequestInfo(
                    requestId = "request-2",
                    toolCallId = "tool-3",
                    title = "Confirm",
                    options = emptyList(),
                    selectedOptionId = null,
                    submitted = false
                ),
                { _: String -> }
            )
        )

        assertUsesTemplateChrome(messageCard)
        assertUsesTemplateChrome(thoughtPanel)
        assertUsesTemplateChrome(toolRow)
        assertUsesTemplateChrome(permissionCard)
    }

    fun testThoughtPanelUsesToggleLinkInsideTemplateChrome() {
        val thoughtPanel = instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.ThoughtPanel",
            arrayOf(
                String::class.java,
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
                "thinking",
                true,
                noOpThoughtToggle
            )
        )

        val toggle = findAllByType(thoughtPanel, ActionLink::class.java).singleOrNull()

        assertNotNull(toggle)
        assertEquals("Hide Thinking", toggle!!.text)
        assertUsesTemplateChrome(thoughtPanel)
    }

    fun testAssistantPromptStatusUsesAnimatedRunningIcon() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-running",
                role = "assistant",
                content = "Working on it"
            ),
            messagePromptState("RUNNING")
        )

        val statusIcon = findByClassName(card, "MessagePromptStatusIcon") as JBLabel
        val animationTimer = statusIcon.javaClass.getDeclaredField("animationTimer").apply {
            isAccessible = true
        }

        assertEquals(AllIcons.Process.Step_1, statusIcon.icon)
        assertNotNull(animationTimer.get(statusIcon))
    }

    fun testConversationRenderShowsEmptyLatestAssistantCardWhileLoading() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val host = JPanel(BorderLayout()).apply {
                setSize(320, 400)
                add(panel, BorderLayout.CENTER)
            }
            val userMessage = AcpSessionService.ChatMessage(
                id = "user-prompt",
                role = "user",
                content = "Question"
            )
            val assistantMessage = AcpSessionService.ChatMessage(
                id = "assistant-empty-loading",
                role = "assistant",
                content = ""
            )

            renderConversation(panel, listOf(userMessage, assistantMessage), isLoading = true)
            layoutRecursively(host)

            val assistantRow = messageRowComponent(panel, "assistant-empty-loading")
            val footer = findByClassName(assistantRow, "MessagePromptFooter")
            val markdownPane = findByClassName(assistantRow, "MarkdownPane")

            assertNotNull(footer)
            assertNull(markdownPane)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testAssistantRunningPromptStatusShowsCancelActionInFooter() {
        var cancelled = false
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-running-cancel",
                role = "assistant",
                content = "Working on it"
            ),
            messagePromptState("RUNNING"),
            onCancelPrompt = { cancelled = true }
        )

        val cancelLink = findAllByType(card, ActionLink::class.java).firstOrNull { it.text == "Cancel" }

        assertNotNull(cancelLink)
        cancelLink!!.doClick()
        assertTrue(cancelled)
    }

    fun testAssistantPromptStatusUsesCompletedIconForEndTurn() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-complete",
                role = "assistant",
                content = "Done"
            ),
            messagePromptState("COMPLETED")
        )

        val statusIcon = findByClassName(card, "MessagePromptStatusIcon") as JBLabel

        assertEquals(AllIcons.General.InspectionsOK, statusIcon.icon)
    }

    fun testAssistantPromptStatusUsesWarningIconForNonEndTurnStopReason() {
        val card = instantiateMessageCard(
            AcpSessionService.ChatMessage(
                id = "assistant-warning",
                role = "assistant",
                content = "Stopped"
            ),
            messagePromptState("WARNING")
        )

        val statusIcon = findByClassName(card, "MessagePromptStatusIcon") as JBLabel

        assertEquals(AllIcons.General.Warning, statusIcon.icon)
    }

    fun testConversationRenderReusesRowComponentForSameMessageId() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-stable",
                role = "assistant",
                content = "Hello"
            )
            renderConversation(panel, listOf(initialMessage))

            val firstComponent = messageRowComponent(panel, "assistant-stable")
            val updatedMessage = initialMessage.copy(content = "Hello again")
            renderConversation(panel, listOf(updatedMessage))

            val secondComponent = messageRowComponent(panel, "assistant-stable")
            val renderedMessage = secondComponent.javaClass.getDeclaredMethod("getMessage").invoke(secondComponent) as AcpSessionService.ChatMessage

            assertSame(firstComponent, secondComponent)
            assertEquals("Hello again", renderedMessage.content)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testConversationRenderReusesPrecreatedAssistantRowWhenFirstContentArrives() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-precreated",
                role = "assistant",
                content = ""
            )
            renderConversation(panel, listOf(initialMessage), isLoading = true)

            val updatedMessage = initialMessage.copy(content = "First assistant reply")
            renderConversation(panel, listOf(updatedMessage), isLoading = true)

            val controllerComponent = messageRowComponent(panel, "assistant-precreated")
            val mountedComponent = mountedMessageRowComponent(panel, 0)
            val markdownPane = findByClassName(controllerComponent, "MarkdownPane") as javax.swing.JEditorPane

            assertEquals(1, mountedMessageRows(panel).size)
            assertSame(controllerComponent, mountedComponent)
            assertTrue(markdownPane.text.contains("First assistant reply"))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testConversationRenderShowsFinalAssistantMarkdownAfterThoughtOnlyLoadingState() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val host = JPanel(BorderLayout()).apply {
                setSize(420, 600)
                add(panel, BorderLayout.CENTER)
            }
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-streaming-final",
                role = "assistant",
                content = "",
                thought = "All artifacts are complete. The change is now ready for implementation. Let me provide the summary.\n",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought(
                        "All artifacts are complete. The change is now ready for implementation. Let me provide the summary.\n"
                    )
                )
            )
            renderConversation(panel, listOf(initialMessage), isLoading = true)
            layoutRecursively(host)

            val finalContent = """

All artifacts created! Ready for implementation.

**Change: `optimize-code-structure`**
- Location: `openspec/changes/optimize-code-structure/`
            """.trimIndent()
            val updatedMessage = initialMessage.copy(
                content = finalContent,
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought(
                        "All artifacts are complete. The change is now ready for implementation. Let me provide the summary.\n"
                    ),
                    AcpSessionService.MessageEntry.Content(finalContent)
                )
            )
            renderConversation(
                panel,
                listOf(updatedMessage),
                isLoading = false,
                lastStopReason = com.agentclientprotocol.model.StopReason.END_TURN
            )
            layoutRecursively(host)

            val assistantRow = messageRowComponent(panel, "assistant-streaming-final")
            val markdownPanes = findAllByType(assistantRow, javax.swing.JEditorPane::class.java)

            assertTrue(markdownPanes.any { it.text.contains("All artifacts created! Ready for implementation.") })
            assertTrue(markdownPanes.any { it.text.contains("optimize-code-structure") })
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testThoughtExpansionStateSurvivesUnrelatedMessageUpdate() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val thoughtMessage = AcpSessionService.ChatMessage(
                id = "assistant-thought",
                role = "assistant",
                content = "Answer",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought("thinking"),
                    AcpSessionService.MessageEntry.Content("Answer")
                )
            )
            val secondMessage = AcpSessionService.ChatMessage(
                id = "assistant-other",
                role = "assistant",
                content = "Other"
            )
            renderConversation(panel, listOf(thoughtMessage, secondMessage))

            expandedThoughts(panel).add("assistant-thought")
            renderConversation(panel, listOf(thoughtMessage, secondMessage.copy(content = "Other updated")))

            val rowComponent = messageRowComponent(panel, "assistant-thought")
            val thoughtPanel = findByClassName(rowComponent, "ThoughtPanel") as javax.swing.JComponent
            val contentPanel = thoughtPanel.javaClass.getDeclaredField("contentPanel").apply {
                isAccessible = true
            }.get(thoughtPanel) as JPanel

            assertTrue(contentPanel.isVisible)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testPermissionCardComponentIsReusedForRequestStateUpdate() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val initialRequest = AcpSessionService.PermissionRequestInfo(
                requestId = "request-stable",
                toolCallId = "tool-stable",
                title = "Run command",
                options = listOf(
                    AcpSessionService.PermissionOptionInfo(
                        optionId = "allow-once",
                        label = "Allow once",
                        kind = "allow_once"
                    )
                ),
                selectedOptionId = null,
                submitted = false
            )
            val updatedRequest = initialRequest.copy(
                selectedOptionId = "allow-once",
                submitted = true
            )
            val message = permissionMessage("assistant-permission", initialRequest)
            renderConversation(panel, listOf(message))

            val firstCard = permissionCard(panel, "request-stable")
            renderConversation(panel, listOf(permissionMessage("assistant-permission", updatedRequest)))

            val secondCard = permissionCard(panel, "request-stable")
            val submitButton = findAllByType(secondCard, JButton::class.java).single()

            assertSame(firstCard, secondCard)
            assertEquals("Submitted", submitButton.text)
            assertFalse(submitButton.isEnabled)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testPermissionCardStructureGrowthRelayoutsMountedConversationRowAndViewport() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val host = JPanel(BorderLayout()).apply {
                setSize(420, 720)
                add(panel, BorderLayout.CENTER)
            }
            val initialRequest = AcpSessionService.PermissionRequestInfo(
                requestId = "request-relayout",
                toolCallId = "tool-relayout",
                title = "Permission",
                options = listOf(
                    AcpSessionService.PermissionOptionInfo(
                        optionId = "allow-once",
                        label = "Allow once",
                        kind = "allow_once"
                    )
                ),
                selectedOptionId = null,
                submitted = false
            )
            renderConversation(panel, listOf(permissionMessage("assistant-permission-relayout", initialRequest)))
            layoutRecursively(host)

            val row = mountedMessageRowComponent(panel, 0)
            val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
                isAccessible = true
            }.get(panel) as JPanel
            val messageScrollPane = panel.javaClass.getDeclaredField("messageScrollPane").apply {
                isAccessible = true
            }.get(panel) as JBScrollPane
            val card = permissionCard(panel, "request-relayout")
            val initialRowHeight = row.height
            val initialPreferredHeight = messagePanel.preferredSize.height
            val initialCard = card

            (card as JPanel).javaClass.getDeclaredMethod(
                "updateRequest",
                AcpSessionService.PermissionRequestInfo::class.java
            ).apply {
                isAccessible = true
            }.invoke(
                card,
                initialRequest.copy(
                    title = "Permission required to run a workspace command that needs a larger amount of explanation",
                    options = listOf(
                        AcpSessionService.PermissionOptionInfo(
                            optionId = "allow-once",
                            label = "Allow once",
                            kind = "allow_once"
                        ),
                        AcpSessionService.PermissionOptionInfo(
                            optionId = "allow-session",
                            label = "Allow for this session",
                            kind = "allow_session"
                        ),
                        AcpSessionService.PermissionOptionInfo(
                            optionId = "reject-once",
                            label = "Reject once",
                            kind = "reject_once"
                        )
                    )
                )
            )
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            layoutRecursively(host)

            val updatedCard = permissionCard(panel, "request-relayout")

            assertSame(initialCard, updatedCard)
            assertTrue("row=${row.bounds} pref=${row.preferredSize}", row.height > initialRowHeight)
            assertTrue(
                "panel=${messagePanel.bounds} pref=${messagePanel.preferredSize}",
                messagePanel.preferredSize.height > initialPreferredHeight
            )
            assertTrue(
                "viewSize=${messageScrollPane.viewport.viewSize} row=${row.bounds}",
                messageScrollPane.viewport.viewSize.height >= row.y + row.height
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testConversationRenderRebuildsRowWhenEntryStructureChanges() {
        val disposable = Disposer.newDisposable()
        val panel = ChatViewPanel(project, disposable)
        try {
            val initialMessage = AcpSessionService.ChatMessage(
                id = "assistant-structure",
                role = "assistant",
                content = "Body"
            )
            renderConversation(panel, listOf(initialMessage))
            val firstComponent = messageRowComponent(panel, "assistant-structure")

            val updatedMessage = AcpSessionService.ChatMessage(
                id = "assistant-structure",
                role = "assistant",
                content = "Body",
                entries = listOf(
                    AcpSessionService.MessageEntry.Thought("thinking"),
                    AcpSessionService.MessageEntry.Content("Body")
                )
            )
            renderConversation(panel, listOf(updatedMessage))

            val secondComponent = messageRowComponent(panel, "assistant-structure")
            val mountedComponent = mountedMessageRowComponent(panel, 0)

            assertNotSame(firstComponent, secondComponent)
            assertSame(secondComponent, mountedComponent)
            assertNotNull(findByClassName(mountedComponent, "ThoughtPanel"))
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun instantiateToolCallRow(toolCall: AcpSessionService.ToolCallInfo): javax.swing.JComponent {
        return instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.ToolCallRow",
            arrayOf(Project::class.java, AcpSessionService.ToolCallInfo::class.java),
            arrayOf(project, toolCall)
        )
    }

    private fun instantiateMessageCard(
        message: AcpSessionService.ChatMessage,
        promptState: Any?,
        onCancelPrompt: () -> Unit = {}
    ): javax.swing.JComponent {
        return instantiatePrivatePanel(
            "github.ponyhuang.acpplugin.toolwindow.ui.MessageCardPanel",
            arrayOf(
                Project::class.java,
                AcpSessionService.ChatMessage::class.java,
                Function2::class.java,
                Function2::class.java,
                Function0::class.java,
                Class.forName("github.ponyhuang.acpplugin.toolwindow.ui.MessagePromptState"),
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
                project,
                message,
                noOpPermissionSubmit,
                noOpPermissionCardCreated,
                onCancelPrompt,
                promptState,
                false,
                noOpThoughtToggle
            )
        )
    }

    private fun messagePromptState(name: String): Any {
        val promptStateClass = Class.forName("github.ponyhuang.acpplugin.toolwindow.ui.MessagePromptState")
        val valueOf = promptStateClass.getDeclaredMethod("valueOf", String::class.java)
        return valueOf.invoke(null, name)
    }

    private fun instantiatePrivatePanel(
        className: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>
    ): javax.swing.JComponent {
        val constructor: Constructor<*> = Class.forName(className).getDeclaredConstructor(*parameterTypes).apply {
            isAccessible = true
        }
        return constructor.newInstance(*args) as javax.swing.JComponent
    }

    private fun findByClassName(root: Component, simpleName: String): Component? {
        if (root.javaClass.simpleName == simpleName) {
            return root
        }
        if (root is Container) {
            root.components.forEach { child ->
                val found = findByClassName(child, simpleName)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun <T : java.awt.Component> findAllByType(
        root: Component,
        type: Class<T>
    ): List<T> {
        val results = mutableListOf<T>()
        if (type.isInstance(root)) {
            results += type.cast(root)
        }
        if (root is Container) {
            root.components.forEach { child ->
                results += findAllByType(child, type)
            }
        }
        return results
    }

    private fun layoutRecursively(root: Component) {
        if (root is Container) {
            root.doLayout()
            root.components.forEach { child -> layoutRecursively(child) }
        }
    }

    private fun createMessagePanelConstraints(row: Int) = java.awt.GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        fill = java.awt.GridBagConstraints.HORIZONTAL
        anchor = java.awt.GridBagConstraints.NORTHWEST
        insets = JBUI.insetsBottom(8)
    }

    private fun createMessagePanelSpacerConstraints(row: Int) = java.awt.GridBagConstraints().apply {
        gridx = 0
        gridy = row
        weightx = 1.0
        weighty = 1.0
        fill = java.awt.GridBagConstraints.BOTH
    }

    private fun assertUsesTemplateChrome(component: Component) {
        val template = findByClassName(component, "MessageTemplatePanel")
        assertNotNull(template)
        assertNotNull(findByClassName(component, "TemplateContentPanel"))
    }

    private fun renderConversation(
        panel: ChatViewPanel,
        messages: List<AcpSessionService.ChatMessage>,
        isLoading: Boolean = false,
        lastStopReason: com.agentclientprotocol.model.StopReason? = null
    ) {
        disableBinding(panel)
        val stateClass = Class.forName("github.ponyhuang.acpplugin.toolwindow.ui.ConversationViewState")
        val constructor = stateClass.getDeclaredConstructor(List::class.java, java.lang.Boolean.TYPE, com.agentclientprotocol.model.StopReason::class.java).apply {
            isAccessible = true
        }
        val state = constructor.newInstance(messages, isLoading, lastStopReason)
        val renderMethod: Method = panel.javaClass.getDeclaredMethod("render", stateClass).apply {
            isAccessible = true
        }
        ApplicationManager.getApplication().invokeAndWait {
            renderMethod.invoke(panel, state)
        }
        repeat(4) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            ApplicationManager.getApplication().invokeAndWait {}
        }
    }

    private fun disableBinding(panel: ChatViewPanel) {
        val uiScope = panel.javaClass.getDeclaredField("uiScope").apply {
            isAccessible = true
        }.get(panel) as CoroutineScope
        uiScope.cancel()
    }

    private fun messageRowComponent(panel: ChatViewPanel, messageId: String): javax.swing.JComponent {
        repeat(4) {
            val controllers = panel.javaClass.getDeclaredField("messageRowControllers").apply {
                isAccessible = true
            }.get(panel) as Map<*, *>
            val controller = controllers[messageId]
            if (controller != null) {
                val componentField = controller.javaClass.getDeclaredField("component").apply {
                    isAccessible = true
                }
                return componentField.get(controller) as javax.swing.JComponent
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }

        val refreshedControllers = panel.javaClass.getDeclaredField("messageRowControllers").apply {
            isAccessible = true
        }.get(panel) as Map<*, *>
        error("Missing row controller for messageId=$messageId available=${refreshedControllers.keys}")
    }

    private fun mountedMessageRows(panel: ChatViewPanel): List<javax.swing.JComponent> {
        val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
            isAccessible = true
        }.get(panel) as JPanel
        return messagePanel.components.filterIsInstance<javax.swing.JComponent>()
            .filterNot { it === spacerComponent(panel) }
    }

    private fun mountedMessageRowComponent(panel: ChatViewPanel, rowIndex: Int): javax.swing.JComponent {
        return mountedMessageRows(panel)[rowIndex]
    }

    private fun spacerComponent(panel: ChatViewPanel): JPanel {
        return panel.javaClass.getDeclaredField("spacerComponent").apply {
            isAccessible = true
        }.get(panel) as JPanel
    }

    @Suppress("UNCHECKED_CAST")
    private fun expandedThoughts(panel: ChatViewPanel): MutableSet<String> {
        return panel.javaClass.getDeclaredField("expandedThoughts").apply {
            isAccessible = true
        }.get(panel) as MutableSet<String>
    }

    private fun permissionCard(panel: ChatViewPanel, requestId: String): javax.swing.JComponent {
        val cards = panel.javaClass.getDeclaredField("permissionCardsByRequestId").apply {
            isAccessible = true
        }.get(panel) as Map<*, *>
        val directMatch = cards[requestId] as? javax.swing.JComponent
        if (directMatch != null) {
            return directMatch
        }
        return mountedMessageRows(panel)
            .asSequence()
            .mapNotNull { findByClassName(it, "PermissionRequestCardPanel") as? javax.swing.JComponent }
            .first()
    }

    private fun permissionMessage(
        messageId: String,
        request: AcpSessionService.PermissionRequestInfo
    ): AcpSessionService.ChatMessage {
        return AcpSessionService.ChatMessage(
            id = messageId,
            role = "assistant",
            content = "",
            entries = listOf(AcpSessionService.MessageEntry.PermissionRequest(request))
        )
    }

    private fun locationInfo(
        displayText: String,
        path: String,
        line: Int? = null
    ) = AcpSessionService.ToolCallLocationInfo(
        displayText = displayText,
        path = path,
        line = line
    )
}
