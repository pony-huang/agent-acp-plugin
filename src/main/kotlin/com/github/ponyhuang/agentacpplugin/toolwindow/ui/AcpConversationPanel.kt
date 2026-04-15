package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import javax.swing.BoxLayout
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
