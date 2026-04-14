package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.border.EmptyBorder

/**
 * @author: pony
 * @date: Created in 18:22 2026/4/14
 */
class AcpConversationPanel(var project: Project) : ScrollablePanel(), Disposable {

    private val messagePanel: JPanel
    private val scrollPane: JBScrollPane

    init {
        layout = BorderLayout()
        border = EmptyBorder(8, 8, 8, 8)

        messagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(247, 247, 247)
        }

        scrollPane = JBScrollPane(messagePanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            background = Color(247, 247, 247)
        }

        add(scrollPane, BorderLayout.CENTER)

        initializeMockMessages()
    }

    private fun initializeMockMessages() {
        // Mock data demonstrating different message types
        val mockMessages = listOf(
            TextMessageItem(
                id = "1",
                content = "你好，请帮我分析这个代码文件",
                isUser = true
            ),
            ToolMessageItem(
                id = "2",
                toolName = "ReadFile",
                toolInput = """{"path": "src/main/java/UserService.java"}""",
                toolResult = "File content loaded successfully...",
                isUser = false
            ),
            ThinkingMessageItem(
                id = "3",
                content = "让我思考一下这个代码的结构...",
                isUser = false
            ),
            TextMessageItem(
                id = "4",
                content = "这是一个 Java 类，包含用户管理功能",
                isUser = true
            ),
            TextMessageItem(
                id = "5",
                content = "感谢提供信息。这个类看起来是一个用户管理服务类，包含基本的 CRUD 操作...",
                isUser = false
            ),
            ImageMessageItem(
                id = "6",
                imagePath = "file:///tmp/screenshot.png",
                caption = "代码结构图",
                isUser = false
            ),
            AudioMessageItem(
                id = "7",
                audioPath = "file:///tmp/voice_message.mp3",
                duration = "0:15",
                isUser = true
            ),
            TextMessageItem(
                id = "1",
                content = "你好，请帮我分析这个代码文件",
                isUser = true
            ),
            ToolMessageItem(
                id = "2",
                toolName = "ReadFile",
                toolInput = """{"path": "src/main/java/UserService.java"}""",
                toolResult = "File content loaded successfully...",
                isUser = false
            ),
            ThinkingMessageItem(
                id = "3",
                content = "让我思考一下这个代码的结构...",
                isUser = false
            ),
            TextMessageItem(
                id = "4",
                content = "这是一个 Java 类，包含用户管理功能",
                isUser = true
            ),
            TextMessageItem(
                id = "5",
                content = "感谢提供信息。这个类看起来是一个用户管理服务类，包含基本的 CRUD 操作...",
                isUser = false
            ),
            ImageMessageItem(
                id = "6",
                imagePath = "file:///tmp/screenshot.png",
                caption = "代码结构图",
                isUser = false
            ),
            AudioMessageItem(
                id = "7",
                audioPath = "file:///tmp/voice_message.mp3",
                duration = "0:15",
                isUser = true
            ),
            TextMessageItem(
                id = "1",
                content = "你好，请帮我分析这个代码文件",
                isUser = true
            ),
            ToolMessageItem(
                id = "2",
                toolName = "ReadFile",
                toolInput = """{"path": "src/main/java/UserService.java"}""",
                toolResult = "File content loaded successfully...",
                isUser = false
            ),
            ThinkingMessageItem(
                id = "3",
                content = "让我思考一下这个代码的结构...",
                isUser = false
            ),
            TextMessageItem(
                id = "4",
                content = "这是一个 Java 类，包含用户管理功能",
                isUser = true
            ),
            TextMessageItem(
                id = "5",
                content = "感谢提供信息。这个类看起来是一个用户管理服务类，包含基本的 CRUD 操作...",
                isUser = false
            ),
            ImageMessageItem(
                id = "6",
                imagePath = "file:///tmp/screenshot.png",
                caption = "代码结构图",
                isUser = false
            ),
            AudioMessageItem(
                id = "7",
                audioPath = "file:///tmp/voice_message.mp3",
                duration = "0:15",
                isUser = true
            )
        )

        mockMessages.forEach { message ->
            addMessage(message)
        }
    }

    fun addMessage(message: ChatMessageItem) {
        val panel = message.createPanel()
        messagePanel.add(panel)
        messagePanel.revalidate()
        messagePanel.repaint()

        // Scroll to bottom
        javax.swing.SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }

    override fun dispose() {
        // No resources to dispose
    }
}

// ==================== Message Type Definitions ====================

enum class MessageContentType {
    TEXT,
    TOOL_CALL,
    THINKING,
    AUDIO,
    IMAGE
}

sealed class ChatMessageItem(
    open val id: String,
    open val isUser: Boolean,
    open val timestamp: Long = System.currentTimeMillis()
) {
    abstract fun createPanel(): JPanel
}

// ==================== Text Message ====================

data class TextMessageItem(
    override val id: String,
    val content: String,
    override val isUser: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : ChatMessageItem(id, isUser, timestamp) {

    override fun createPanel(): JPanel {
        val bgColor = if (isUser) Color(219, 235, 253) else Color(255, 255, 255)
        val alignment = if (isUser) FlowLayout.RIGHT else FlowLayout.LEFT

        return MessageBubblePanel(
            backgroundColor = bgColor,
            alignment = alignment,
            maxWidthRatio = 0.75
        ).apply {
            add(JLabel("<html><body style='font-family: sans-serif; font-size: 13px; max-width: 400px; line-height: 1.4;'>${content}</body></html>"))
        }
    }
}

// ==================== Tool Message ====================

data class ToolMessageItem(
    override val id: String,
    val toolName: String,
    val toolInput: String,
    val toolResult: String? = null,
    override val isUser: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : ChatMessageItem(id, isUser, timestamp) {

    override fun createPanel(): JPanel {
        val bgColor = Color(243, 245, 249)
        var alignment = FlowLayout.LEFT

        return MessageBubblePanel(
            backgroundColor = bgColor,
            alignment = alignment,
            maxWidthRatio = 0.85,
            padding = Insets(12, 14, 12, 14)
        ).apply {
            // Tool name header
            add(JLabel("<html><body style='font-family: sans-serif; font-size: 12px; font-weight: bold; color: #5c6bc0;'>⚙️ ${toolName}</body></html>").apply {
                alignment = FlowLayout.LEFT
            })

            // Tool input
            add(JLabel("<html><body style='font-family: monospace; font-size: 11px; color: #37474f; background: #e8eaf6; padding: 8px; border-radius: 4px;'>${toolInput}</body></html>").apply {
                alignment = FlowLayout.LEFT
            })

            // Tool result if present
            toolResult?.let { result ->
                add(JLabel("<html><body style='font-family: sans-serif; font-size: 12px; color: #2e7d32; margin-top: 6px;'>✓ ${result}</body></html>").apply {
                    alignment = FlowLayout.LEFT
                })
            }
        }
    }
}

// ==================== Thinking Message ====================

data class ThinkingMessageItem(
    override val id: String,
    val content: String,
    override val isUser: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : ChatMessageItem(id, isUser, timestamp) {

    override fun createPanel(): JPanel {
        val bgColor = Color(255, 253, 231)
        var alignment = FlowLayout.LEFT

        return MessageBubblePanel(
            backgroundColor = bgColor,
            alignment = alignment,
            maxWidthRatio = 0.75,
            padding = Insets(10, 14, 10, 14)
        ).apply {
            // Thinking indicator
            add(JLabel("<html><body style='font-family: sans-serif; font-size: 12px; color: #f9a825;'>💭 思考中...</body></html>").apply {
                alignment = FlowLayout.LEFT
            })

            add(JLabel("<html><body style='font-family: sans-serif; font-size: 13px; color: #5d4037; font-style: italic;'>${content}</body></html>").apply {
                alignment = FlowLayout.LEFT
            })
        }
    }
}

// ==================== Image Message ====================

data class ImageMessageItem(
    override val id: String,
    val imagePath: String,
    val caption: String? = null,
    override val isUser: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : ChatMessageItem(id, isUser, timestamp) {

    override fun createPanel(): JPanel {
        val bgColor = if (isUser) Color(219, 235, 253) else Color(255, 255, 255)
        var alignment = if (isUser) FlowLayout.RIGHT else FlowLayout.LEFT

        return MessageBubblePanel(
            backgroundColor = bgColor,
            alignment = alignment,
            maxWidthRatio = 0.6,
            padding = Insets(8, 8, 8, 8)
        ).apply {
            // Placeholder for image (actual image rendering would require additional setup)
            add(JLabel("<html><body style='font-family: sans-serif; font-size: 12px; text-align: center;'>🖼️<br/>图片预览<br/><i>${imagePath}</i></body></html>").apply {
                alignment = FlowLayout.CENTER
                preferredSize = Dimension(200, 150)
            })

            caption?.let {
                add(JLabel("<html><body style='font-family: sans-serif; font-size: 11px; color: #666;'>${it}</body></html>").apply {
                    alignment = FlowLayout.CENTER
                })
            }
        }
    }
}

// ==================== Audio Message ====================

data class AudioMessageItem(
    override val id: String,
    val audioPath: String,
    val duration: String? = null,
    override val isUser: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : ChatMessageItem(id, isUser, timestamp) {

    override fun createPanel(): JPanel {
        val bgColor = if (isUser) Color(219, 235, 253) else Color(255, 255, 255)
        var alignment = if (isUser) FlowLayout.RIGHT else FlowLayout.LEFT

        return MessageBubblePanel(
            backgroundColor = bgColor,
            alignment = alignment,
            maxWidthRatio = 0.5,
            padding = Insets(10, 14, 10, 14)
        ).apply {
            add(JLabel("<html><body style='font-family: sans-serif; font-size: 13px;'>🔊 语音消息${duration?.let { " ($it)" } ?: ""}</body></html>").apply {
                alignment = FlowLayout.CENTER
            })

            add(JLabel("<html><body style='font-family: sans-serif; font-size: 10px; color: #999;'>${audioPath}</body></html>").apply {
                alignment = FlowLayout.CENTER
            })
        }
    }
}

// ==================== Message Bubble Panel Base ====================

class MessageBubblePanel(
    private val backgroundColor: Color,
    private val alignment: Int,
    private val maxWidthRatio: Double = 0.75,
    private val padding: Insets = Insets(10, 14, 10, 14)
) : JPanel(BorderLayout()) {

    init {
        background = Color(247, 247, 247)
        border = EmptyBorder(4, 8, 4, 8)
        maximumSize = Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)
    }

    override fun doLayout() {
        super.doLayout()
        // Apply max width constraint based on parent width
        val parentWidth = parent?.width ?: 600
        val maxWidth = (parentWidth * maxWidthRatio).toInt()
        for (component in components) {
            if (component.maximumSize.width > maxWidth) {
                component.maximumSize = Dimension(maxWidth, component.maximumSize.height)
            }
        }
    }

    override fun add(comp: Component): Component? {
        val wrapper = JPanel(FlowLayout(alignment, 0, 0))
        wrapper.background = backgroundColor
        wrapper.border = EmptyBorder(padding)
        wrapper.add(comp)
        return super.add(wrapper)
    }
}