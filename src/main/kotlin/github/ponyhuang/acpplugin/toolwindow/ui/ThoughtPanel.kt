package github.ponyhuang.acpplugin.toolwindow.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

internal class ThoughtPanel(
    thought: String,
    expanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private val maxVisibleHeight = JBUI.scale(240)
    private val contentPanel = JPanel(BorderLayout())
    private val toggle = ActionLink("")
    private val markdownPane = MarkdownPane(thought)
    private val scrollPane =
        object : JBScrollPane(markdownPane) {
            override fun getPreferredSize(): Dimension {
                val preferred = super.getPreferredSize()
                return Dimension(preferred.width, preferred.height.coerceAtMost(maxVisibleHeight))
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, maxVisibleHeight)
        }
    private var toggleHandler: ((Boolean) -> Unit)? = onThoughtToggled

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)

        chrome.contentPanel.layout = BorderLayout(0, JBUI.scale(6))

        toggle.addActionListener {
            val nextExpanded = !contentPanel.isVisible
            setExpanded(nextExpanded)
            toggleHandler?.invoke(nextExpanded)
        }
        chrome.contentPanel.add(toggle, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.isOpaque = false
        scrollPane.viewport.isOpaque = false
        scrollPane.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.verticalScrollBar.unitIncrement = JBUI.scale(16)
        contentPanel.add(scrollPane, BorderLayout.CENTER)
        chrome.contentPanel.add(contentPanel, BorderLayout.CENTER)
        update(thought, expanded, onThoughtToggled)
    }

    fun update(thought: String, expanded: Boolean, onThoughtToggled: (Boolean) -> Unit) {
        markdownPane.updateContent(thought)
        toggleHandler = onThoughtToggled
        setExpanded(expanded)
    }

    private fun setExpanded(expanded: Boolean) {
        val nextToggleText = if (expanded) "Hide Thinking" else "Show Thinking"
        if (contentPanel.isVisible == expanded && toggle.text == nextToggleText) {
            return
        }
        contentPanel.isVisible = expanded
        toggle.text = nextToggleText
        contentPanel.revalidate()
        revalidate()
        repaint()
    }
}
