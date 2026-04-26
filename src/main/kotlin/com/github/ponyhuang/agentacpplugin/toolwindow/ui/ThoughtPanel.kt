package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

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
        contentPanel.add(markdownPane, BorderLayout.CENTER)
        chrome.contentPanel.add(contentPanel, BorderLayout.CENTER)
        update(thought, expanded, onThoughtToggled)
    }

    fun update(thought: String, expanded: Boolean, onThoughtToggled: (Boolean) -> Unit) {
        markdownPane.updateContent(thought)
        toggleHandler = onThoughtToggled
        setExpanded(expanded)
    }

    private fun setExpanded(expanded: Boolean) {
        contentPanel.isVisible = expanded
        toggle.text = if (expanded) "Hide Thinking" else "Show Thinking"
        revalidate()
        repaint()
    }
}
