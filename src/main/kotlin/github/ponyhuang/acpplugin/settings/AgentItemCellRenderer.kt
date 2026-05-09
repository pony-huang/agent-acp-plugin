package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.JPanel
import javax.swing.SwingConstants

internal class AgentItemCellRenderer(
    private val iconResolver: (AgentItem, Int) -> Icon,
    private val primaryActionText: (AgentItem) -> String,
    private val primaryActionEnabled: (AgentItem) -> Boolean,
) : ListCellRenderer<AgentItem> {
    companion object {
        private val ICON_BOX_SIZE = JBUI.scale(40)
        private val ICON_RENDER_SIZE = JBUI.scale(24)
    }

    private val titleLabel = JBLabel()
    private val versionLabel = JBLabel()
    private val actionButton = JButton()
    private val iconLabel = JBLabel()
    private val textPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(titleLabel)
        add(Box.createVerticalStrut(JBUI.scale(2)))
        add(versionLabel)
    }
    private val panel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        border = JBUI.Borders.empty(8, 10)
        add(iconLabel, BorderLayout.WEST)
        add(textPanel, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(actionButton)
        }, BorderLayout.EAST)
    }

    init {
        titleLabel.font = JBFont.label().deriveFont(Font.BOLD)
        versionLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        iconLabel.horizontalAlignment = SwingConstants.CENTER
        iconLabel.verticalAlignment = SwingConstants.CENTER
        iconLabel.preferredSize = Dimension(ICON_BOX_SIZE, ICON_BOX_SIZE)
        iconLabel.minimumSize = iconLabel.preferredSize
        iconLabel.maximumSize = iconLabel.preferredSize
        iconLabel.border = JBUI.Borders.empty()
        actionButton.isFocusable = false
        actionButton.isOpaque = false
        actionButton.isContentAreaFilled = false
        actionButton.margin = Insets(0, 0, 0, 0)
    }

    override fun getListCellRendererComponent(
        list: JList<out AgentItem>,
        value: AgentItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): JComponent {
        titleLabel.text = value.title
        val statusText = value.listStatusText()
        versionLabel.text = statusText
        versionLabel.isVisible = !statusText.isNullOrBlank()
        val actionText = primaryActionText(value)
        actionButton.text = actionText
        actionButton.isEnabled = primaryActionEnabled(value)
        styleActionButton(actionButton, actionText)
        iconLabel.name = "agentIcon:${value.key}"
        iconLabel.icon = iconResolver(value, ICON_RENDER_SIZE)
        iconLabel.text = null

        panel.background = if (isSelected) list.selectionBackground else list.background
        panel.foreground = if (isSelected) list.selectionForeground else list.foreground
        panel.isOpaque = true
        return panel
    }

    fun measureActionWidth(item: AgentItem): Int {
        val actionText = primaryActionText(item)
        actionButton.text = actionText
        styleActionButton(actionButton, actionText)
        return actionButton.preferredSize.width
    }

    private fun styleActionButton(button: JButton, actionText: String) {
        val borderColor = when (actionText) {
            MyBundle.message("settings.install"),
            MyBundle.message("settings.upgrade") -> JBColor(0x5FAF5F, 0x5FAF5F)

            MyBundle.message("settings.uninstall") -> JBColor(0x4C88FF, 0x4C88FF)
            else -> JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        }
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 1, 1, 1, borderColor),
            JBUI.Borders.empty(3, 10)
        )
    }
}
