package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.github.ponyhuang.agentacpplugin.services.AcpSessionService
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JRadioButton

internal class PermissionRequestCardPanel(
    request: AcpSessionService.PermissionRequestInfo,
    onSubmit: (String) -> Unit
) : JPanel() {
    private var currentRequest = request
    private val titleLabel = JBLabel()
    private val buttonGroup = ButtonGroup()
    private val radios = mutableListOf<Pair<AcpSessionService.PermissionOptionInfo, JRadioButton>>()
    private val submitButton = JButton().apply {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        addActionListener {
            val selectedOption = radios.firstOrNull { (_, radio) -> radio.isSelected }?.first ?: return@addActionListener
            onSubmit(selectedOption.optionId)
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        layout = BorderLayout()
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false

        val chrome = nestedTemplatePanel()
        add(chrome, BorderLayout.CENTER)
        chrome.contentPanel.layout = BoxLayout(chrome.contentPanel, BoxLayout.Y_AXIS)

        chrome.contentPanel.add(
            JBLabel("Allow?").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = LEFT_ALIGNMENT
            }
        )
        chrome.contentPanel.add(
            titleLabel.apply {
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = LEFT_ALIGNMENT
            }
        )
        rebuildOptions()
    }

    fun updateRequest(request: AcpSessionService.PermissionRequestInfo) {
        val structureChanged =
            currentRequest.options != request.options || currentRequest.title != request.title
        currentRequest = request
        if (structureChanged) {
            rebuildOptions()
        } else {
            applyRequestState()
        }
        revalidate()
        repaint()
    }

    private fun rebuildOptions() {
        titleLabel.text = currentRequest.title

        val contentPanel = templateContentPanel()
        while (contentPanel.componentCount > 2) {
            contentPanel.remove(2)
        }
        while (buttonGroup.elements.hasMoreElements()) {
            buttonGroup.remove(buttonGroup.elements.nextElement())
        }
        radios.clear()

        if (currentRequest.options.isEmpty()) {
            contentPanel.add(
                JBLabel("No permission options were provided by the agent.").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                }
            )
        } else {
            currentRequest.options.forEachIndexed { index, option ->
                val radio = JRadioButton(buildPermissionOptionLabel(option)).apply {
                    isOpaque = false
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.emptyBottom(if (index == currentRequest.options.lastIndex) 0 else 6)
                    alignmentX = LEFT_ALIGNMENT
                }
                buttonGroup.add(radio)
                radios += option to radio
                contentPanel.add(radio)
            }

            contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            contentPanel.add(submitButton)
        }

        applyRequestState()
    }

    private fun applyRequestState() {
        titleLabel.text = currentRequest.title
        radios.forEachIndexed { index, (option, radio) ->
            radio.isSelected =
                currentRequest.selectedOptionId == option.optionId ||
                    (currentRequest.selectedOptionId == null && index == 0)
            radio.isEnabled = !currentRequest.submitted
        }
        submitButton.text = if (currentRequest.submitted) "Submitted" else "Submit"
        submitButton.isEnabled = !currentRequest.submitted
    }

    private fun templateContentPanel(): JPanel {
        return (getComponent(0) as MessageTemplatePanel).contentPanel
    }
}
