package github.ponyhuang.acpplugin.toolwindow.ui

import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JViewport
import javax.swing.SwingUtilities

internal class MarkdownPane(content: String) : JEditorPane() {
    private var lastLaidOutWidth: Int = -1

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun getPreferredSize(): Dimension {
        val availableWidth = resolveAvailableWidth()
        if (availableWidth != null) {
            return Dimension(availableWidth, preferredHeightForWidth(availableWidth))
        }
        return super.getPreferredSize()
    }

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int =
        JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int =
        visibleRect?.height?.coerceAtLeast(JBUI.scale(16)) ?: JBUI.scale(16)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val widthChanged = width > 0 && width != lastLaidOutWidth
        super.setBounds(x, y, width, height)
        if (widthChanged) {
            lastLaidOutWidth = width
            SwingUtilities.invokeLater {
                revalidateAncestorChain()
            }
        }
    }

    init {
        alignmentX = LEFT_ALIGNMENT
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty()
        editorKit = HTMLEditorKitBuilder.simple()
        updateContent(content)
    }

    fun updateContent(content: String) {
        text = renderHtml(content)
        caretPosition = 0
        revalidateAncestorChain()
    }

    private fun resolveAvailableWidth(): Int? {
        var candidate: JComponent? = parent as? JComponent
        while (candidate != null) {
            if (candidate is JViewport) {
                val viewportWidth = candidate.extentSize.width.takeIf { it > 0 } ?: candidate.width
                if (viewportWidth > 0) {
                    return viewportWidth.coerceAtLeast(1)
                }
            }
            val innerWidth = candidate.width - candidate.insets.left - candidate.insets.right
            if (innerWidth > 0) {
                return innerWidth.coerceAtLeast(1)
            }
            candidate = candidate.parent as? JComponent
        }
        return width.takeIf { it > 0 }
    }

    private fun preferredHeightForWidth(width: Int): Int {
        val previousSize = size
        super.setSize(width, Short.MAX_VALUE.toInt())
        val preferred = super.getPreferredSize()
        size = previousSize
        return preferred.height
    }
}

private val markdownFlavour = GFMFlavourDescriptor()

private fun markdownHtmlBody(text: String): String {
    val parsedTree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(text)
    return HtmlGenerator(text, parsedTree, markdownFlavour).generateHtml()
}

private fun compactLabelHtmlBody(body: String): String {
    return body
        .replace(Regex("^<p>(.*)</p>$", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace("<p>", "")
        .replace("</p>", "<br/>")
        .removeSuffix("<br/>")
}

internal fun renderHtml(text: String): String {
    val body = markdownHtmlBody(text)
    return """
        <html>
        <head>
        <style>
        body {
            margin: 0;
            padding: 0;
            white-space: normal;
            word-wrap: break-word;
        }
        p { margin: 0 0 6px 0; }
        p:last-child { margin-bottom: 0; }
        ul, ol { margin-top: 0; margin-bottom: 0; padding-left: 18px; }
        p, li, blockquote, div, span, td, th {
            white-space: normal;
            word-wrap: break-word;
        }
        pre, code {
            margin: 0;
            white-space: pre-wrap;
            word-wrap: break-word;
        }
        </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

internal fun renderLabelHtml(text: String): String = "<html>${compactLabelHtmlBody(markdownHtmlBody(text))}</html>"
