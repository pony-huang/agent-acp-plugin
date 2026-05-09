package github.ponyhuang.acpplugin.settings

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpAgentRegistryService
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

internal class AgentDetailPanel(
    private val onInstallAgent: (
        AcpAgentRegistryService.RegistryAgent,
        AcpPluginSettings.InstalledAgentSetting?,
        Boolean
    ) -> Unit,
    private val onUninstallAgent: (AcpPluginSettings.InstalledAgentSetting) -> Unit,
) {
    val component: JComponent

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyPanel = JBPanelWithEmptyText()
    private val contentPanel = JPanel(BorderLayout(JBUI.scale(0), JBUI.scale(12)))
    private val titleLabel = JBLabel()
    private val descriptionArea = JTextArea()
    private val overviewPanel = JPanel()
    private val detailsPanel = JPanel()
    private val tabbedPane = JBTabbedPane()
    private val installButton = JButton(MyBundle.message("settings.install"))
    private val upgradeButton = JButton(MyBundle.message("settings.upgrade"))
    private val uninstallButton = JButton(MyBundle.message("settings.uninstall"))

    private var currentState: DetailState? = null

    init {
        emptyPanel.emptyText.text = MyBundle.message("settings.agentDetailsEmpty")
        cardPanel.add(emptyPanel, "empty")

        titleLabel.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size.toFloat() + JBUI.scale(5))
        titleLabel.horizontalAlignment = SwingConstants.LEFT
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT

        descriptionArea.isEditable = false
        descriptionArea.isOpaque = false
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.border = JBUI.Borders.empty()
        descriptionArea.font = JBFont.label()
        descriptionArea.alignmentX = Component.LEFT_ALIGNMENT

        overviewPanel.layout = BoxLayout(overviewPanel, BoxLayout.Y_AXIS)
        overviewPanel.isOpaque = false
        detailsPanel.layout = BoxLayout(detailsPanel, BoxLayout.Y_AXIS)
        detailsPanel.isOpaque = false

        tabbedPane.addTab(MyBundle.message("settings.overviewTab"), JBScrollPane(overviewPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        })
        tabbedPane.addTab(MyBundle.message("settings.detailsTab"), JBScrollPane(detailsPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        })

        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(installButton)
            add(upgradeButton)
            add(uninstallButton)
        }

        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(descriptionArea)
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(actionPanel)
        }

        contentPanel.border = JBUI.Borders.empty(16)
        contentPanel.add(headerPanel, BorderLayout.NORTH)
        contentPanel.add(tabbedPane, BorderLayout.CENTER)

        cardPanel.add(contentPanel, "content")
        component = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            add(cardPanel, BorderLayout.CENTER)
        }

        installButton.addActionListener {
            val state = currentState ?: return@addActionListener
            val agent = state.registryAgent ?: return@addActionListener
            onInstallAgent(agent, null, false)
        }
        upgradeButton.addActionListener {
            val state = currentState ?: return@addActionListener
            val agent = state.registryAgent ?: return@addActionListener
            onInstallAgent(agent, state.uninstallTarget, true)
        }
        uninstallButton.addActionListener {
            currentState?.uninstallTarget?.let(onUninstallAgent)
        }

        showEmpty()
    }

    fun showEmpty() {
        currentState = null
        titleLabel.text = ""
        descriptionArea.text = ""
        overviewPanel.removeAll()
        detailsPanel.removeAll()
        updateActions()
        cardLayout.show(cardPanel, "empty")
    }

    fun show(state: DetailState) {
        currentState = state
        titleLabel.text = state.title
        descriptionArea.text = state.description
        fillLines(overviewPanel, state.overviewLines)
        fillLines(detailsPanel, state.detailLines)
        updateActions()
        cardLayout.show(cardPanel, "content")
    }

    private fun fillLines(panel: JPanel, lines: List<String>) {
        panel.removeAll()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                panel.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
            panel.add(createDetailLine(line))
        }
        panel.revalidate()
        panel.repaint()
    }

    private fun createDetailLine(text: String): JComponent {
        return JTextArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            font = JBFont.label()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun updateActions() {
        val state = currentState
        installButton.isEnabled = state?.installEnabled == true
        upgradeButton.isEnabled = state?.upgradeEnabled == true
        uninstallButton.isEnabled = state?.uninstallEnabled == true
    }
}
