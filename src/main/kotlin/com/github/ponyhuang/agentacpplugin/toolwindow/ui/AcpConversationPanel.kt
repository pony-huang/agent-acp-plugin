package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * @author: pony
 * @date: Created in 18:22 2026/4/14
 */
class AcpConversationPanel(var project: Project) : ScrollablePanel() {

    private val messagePanel: JPanel

    init {
        layout = BorderLayout()
        border = EmptyBorder(8, 8, 8, 8)

        messagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        add(messagePanel, BorderLayout.CENTER)
    }

}

val flavour = GFMFlavourDescriptor()

private fun renderHtml(text: String): String {
    val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
    return HtmlGenerator(text, parsedTree, GFMFlavourDescriptor()).generateHtml()
}


class MessageViewPanel(
    var role: String,
    var content: String
) : BorderLayoutPanel() {

    private val editorPane: JEditorPane

    init {
        background = UIUtil.getPanelBackground()
        maximumSize = Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)
        isOpaque = false
        alignmentX = 0.0f
        editorPane = JEditorPane().apply {
            isEditable = false
            isOpaque = true
            background = UIUtil.getPanelBackground()
            foreground = UIUtil.getLabelForeground()
            val htmlEditorKit = HTMLEditorKitBuilder.simple()
            val styleSheet = htmlEditorKit.styleSheet
            styleSheet.addRule(
                """
                h1 { font-size: 14pt; margin-bottom: 2px; }
                h2 { font-size: 13pt; margin-bottom: 2px; }
                h3 { font-size: 12pt; margin-bottom: 2px; }
                h4 { font-size: 11pt; margin-bottom: 2px; }
            """.trimIndent()
            )
            editorKit = htmlEditorKit
        }
        editorPane.text = renderHtml(content)
        addToCenter(panel {
            row {
                cell(JBLabel(role).apply {
                    isOpaque = false
                    background = UIUtil.getEditorPaneBackground()
                }).align(AlignX.LEFT)
            }
            row {
                cell(editorPane).align(AlignX.LEFT)
            }
        }.apply {
            background = UIUtil.getPanelBackground()
            isOpaque = false
        })
    }
}