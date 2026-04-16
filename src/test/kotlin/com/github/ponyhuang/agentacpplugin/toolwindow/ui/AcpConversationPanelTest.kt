package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JButton
import javax.swing.JRadioButton
import java.lang.reflect.Constructor

class AcpConversationPanelTest : BasePlatformTestCase() {

    fun testMessagePanelUsesVerticalLayout() {
        val disposable = Disposer.newDisposable()
        val panel = AcpConversationPanel(project, disposable)
        try {
            val messagePanel = panel.javaClass.getDeclaredField("messagePanel").apply {
                isAccessible = true
            }.get(panel) as javax.swing.JPanel

            assertEquals("com.intellij.ui.components.panels.VerticalLayout", messagePanel.layout::class.java.name)
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
                java.lang.Boolean.TYPE,
                Function1::class.java
            ),
            arrayOf(
                message,
                false,
                { _: Boolean -> }
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

    private fun instantiatePrivatePanel(
        className: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>
    ): javax.swing.JComponent {
        val constructor: Constructor<*> = Class.forName(className).getDeclaredConstructor(*parameterTypes).apply {
            isAccessible = true
        }
        return constructor.newInstance(*args) as javax.swing.JComponent
    }

    private fun findByClassName(root: java.awt.Component, simpleName: String): java.awt.Component? {
        if (root.javaClass.simpleName == simpleName) {
            return root
        }
        if (root is java.awt.Container) {
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
        root: java.awt.Component,
        type: Class<T>
    ): List<T> {
        val results = mutableListOf<T>()
        if (type.isInstance(root)) {
            results += type.cast(root)
        }
        if (root is java.awt.Container) {
            root.components.forEach { child ->
                results += findAllByType(child, type)
            }
        }
        return results
    }
}
