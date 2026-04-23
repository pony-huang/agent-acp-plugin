package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.lang.reflect.Constructor
import java.awt.Component
import java.awt.Container
import java.awt.GridBagLayout
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JRadioButton

class AcpConversationPanelTest : BasePlatformTestCase() {
    private val noOpPermissionSubmit: (String, String) -> Unit = { _, _ -> }
    private val noOpPermissionCardCreated: (String, javax.swing.JComponent) -> Unit = { _, _ -> }
    private val noOpCancel: () -> Unit = { }
    private val noOpThoughtToggle: (Boolean) -> Unit = { }

    fun testMessagePanelUsesGridBagLayoutWithCompactHorizontalInset() {
        val disposable = Disposer.newDisposable()
        val panel = AcpChatViewPanel(project, disposable)
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
        val panel = AcpChatViewPanel(project, disposable)
        try {
            panel.setSize(480, 720)

            val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
                isAccessible = true
            }.get(panel) as JPanel

            val messageCard = instantiatePrivatePanel(
                "com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessageCardPanel",
                arrayOf(
                    AcpSessionService.ChatMessage::class.java,
                    Function2::class.java,
                    Function2::class.java,
                    Function0::class.java,
                    Class.forName("com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessagePromptState"),
                    java.lang.Boolean.TYPE,
                    Function1::class.java
                ),
                arrayOf<Any?>(
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
                "com.github.ponyhuang.agentacpplugin.toolwindow.ui.PermissionRequestCardPanel",
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
        val panel = AcpChatViewPanel(project, disposable)
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
                    locations = listOf("src/Main.kt:12"),
                    contentSummary = "done"
                )
            )
        )
        val card = instantiatePrivatePanel(
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessageCardPanel",
            arrayOf(
                AcpSessionService.ChatMessage::class.java,
                Function2::class.java,
                Function2::class.java,
                Function0::class.java,
                Class.forName("com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessagePromptState"),
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
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
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.PermissionRequestCardPanel",
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

    fun testToolCallRowUsesEmojiTitleAndStaticStatusIcon() {
        val row = instantiateToolCallRow(
            AcpSessionService.ToolCallInfo(
                toolCallId = "tool-1",
                title = "Read file",
                status = "completed",
                kind = "read"
            )
        )

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "📖 Read Read file" }
        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel

        assertNotNull(titleLabel)
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

        val titleLabel = findAllByType(row, JBLabel::class.java).firstOrNull { it.text == "🔍 Search Search workspace" }
        val statusIcon = findByClassName(row, "ToolStatusIcon") as JBLabel
        val animationTimer = statusIcon.javaClass.getDeclaredField("animationTimer").apply {
            isAccessible = true
        }

        assertNotNull(titleLabel)
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
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessageCardPanel",
            arrayOf(
                AcpSessionService.ChatMessage::class.java,
                Function2::class.java,
                Function2::class.java,
                Function0::class.java,
                Class.forName("com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessagePromptState"),
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
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
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.PermissionRequestCardPanel",
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
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.ThoughtPanel",
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

    private fun instantiateToolCallRow(toolCall: AcpSessionService.ToolCallInfo): javax.swing.JComponent {
        return instantiatePrivatePanel(
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.ToolCallRow",
            arrayOf(AcpSessionService.ToolCallInfo::class.java),
            arrayOf(toolCall)
        )
    }

    private fun instantiateMessageCard(
        message: AcpSessionService.ChatMessage,
        promptState: Any?,
        onCancelPrompt: () -> Unit = {}
    ): javax.swing.JComponent {
        return instantiatePrivatePanel(
            "com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessageCardPanel",
            arrayOf(
                AcpSessionService.ChatMessage::class.java,
                Function2::class.java,
                Function2::class.java,
                Function0::class.java,
                Class.forName("com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessagePromptState"),
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
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
        val promptStateClass = Class.forName("com.github.ponyhuang.agentacpplugin.toolwindow.ui.MessagePromptState")
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
}
