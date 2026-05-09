package github.ponyhuang.acpplugin.toolwindow.ui.chat

import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import github.ponyhuang.acpplugin.toolwindow.ui.MarkdownPane
import github.ponyhuang.acpplugin.toolwindow.ui.nestedMessageBubblePanel
import github.ponyhuang.acpplugin.toolwindow.ui.revalidateAncestorChain
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal class ThoughtPanel(
    thought: String,
    expanded: Boolean,
    onThoughtToggled: (Boolean) -> Unit
) : JPanel() {
    private val contentPanel = JPanel(BorderLayout())
    private val toggle = ActionLink("")
    private val markdownPane = MarkdownPane(thought)
    private var toggleHandler: ((Boolean) -> Unit)? = onThoughtToggled

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val bubblePanel = nestedMessageBubblePanel()
        add(bubblePanel, BorderLayout.CENTER)

        bubblePanel.contentPanel.layout = BorderLayout(0, JBUI.scale(6))

        toggle.addActionListener {
            val nextExpanded = !contentPanel.isVisible
            setExpanded(nextExpanded)
            toggleHandler?.invoke(nextExpanded)
        }
        bubblePanel.contentPanel.add(toggle, BorderLayout.NORTH)

        contentPanel.isOpaque = false
        markdownPane.border = JBUI.Borders.empty()
        contentPanel.add(markdownPane, BorderLayout.CENTER)
        bubblePanel.contentPanel.add(contentPanel, BorderLayout.CENTER)
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
        revalidateAncestorChain()
        if (expanded) {
            SwingUtilities.invokeLater {
                markdownPane.revalidateAncestorChain()
            }
        }
    }
}
