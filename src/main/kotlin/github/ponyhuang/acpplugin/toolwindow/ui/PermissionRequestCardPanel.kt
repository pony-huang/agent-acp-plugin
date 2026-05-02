package github.ponyhuang.acpplugin.toolwindow.ui

import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AcpSessionService
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
    private val statusIcon = ToolStatusIcon(request.status)
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
            JBLabel(MyBundle.message("permission.allow")).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(6)
                alignmentX = LEFT_ALIGNMENT
            }
        )
        chrome.contentPanel.add(
            JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(8)
                add(titleLabel, BorderLayout.WEST)
                add(statusIcon, BorderLayout.EAST)
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
        revalidateAncestorChain()
    }

    private fun rebuildOptions() {
        titleLabel.text = currentRequest.title
        titleLabel.foreground = UIUtil.getLabelForeground()

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
                JBLabel(MyBundle.message("permission.noOptions")).apply {
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
        statusIcon.updateStatus(currentRequest.status)
        radios.forEachIndexed { index, (option, radio) ->
            radio.isSelected =
                currentRequest.selectedOptionId == option.optionId ||
                    (currentRequest.selectedOptionId == null && index == 0)
            radio.isEnabled = !currentRequest.submitted
        }
        submitButton.text = if (currentRequest.submitted) MyBundle.message("permission.submitted") else MyBundle.message("permission.submit")
        submitButton.isEnabled = !currentRequest.submitted
    }

    private fun templateContentPanel(): JPanel {
        return (getComponent(0) as MessageTemplatePanel).contentPanel
    }
}
