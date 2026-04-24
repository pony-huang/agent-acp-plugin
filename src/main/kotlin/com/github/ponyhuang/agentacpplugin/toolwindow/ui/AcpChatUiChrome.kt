package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

internal class MessageTemplatePanel(
    private val backgroundColor: JBColor,
    private val borderColor: JBColor,
    private val arc: Int,
    padding: Insets
) : JPanel(BorderLayout()) {
    val contentPanel = TemplateContentPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
    }

    init {
        isOpaque = false
        add(contentPanel, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = backgroundColor
            g2.fill(
                RoundRectangle2D.Float(
                    0f,
                    0f,
                    width.toFloat() - 1f,
                    height.toFloat() - 1f,
                    JBUI.scale(arc).toFloat(),
                    JBUI.scale(arc).toFloat()
                )
            )
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = borderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(arc), JBUI.scale(arc))
        } finally {
            g2.dispose()
        }
    }
}

internal class TemplateContentPanel : JPanel()

internal fun nestedTemplatePanel(): MessageTemplatePanel {
    val base = UIUtil.getPanelBackground()
    return MessageTemplatePanel(
        backgroundColor = JBColor(
            ColorUtil.mix(base, UIUtil.getLabelForeground(), 0.03),
            ColorUtil.mix(base, UIUtil.getLabelForeground(), 0.07)
        ),
        borderColor = JBColor.namedColor("Component.borderColor", JBColor(0xC9C9C9, 0x5E6068)),
        arc = 14,
        padding = JBUI.insets(8)
    )
}

