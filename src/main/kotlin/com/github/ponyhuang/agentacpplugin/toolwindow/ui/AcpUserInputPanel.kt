package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import com.github.ponyhuang.agentacpplugin.services.AgentNotifier
import com.github.ponyhuang.agentacpplugin.services.AgentRegistry
import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowComposerState
import com.github.ponyhuang.agentacpplugin.toolwindow.action.AgentComboBoxAction
import com.github.ponyhuang.agentacpplugin.toolwindow.action.ModelComboBoxAction
import com.github.ponyhuang.agentacpplugin.toolwindow.action.PlanComboBoxAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton

/**
 * @author: pony
 */
class AcpUserInputPanel(
    val project: Project,
    agentItems: List<AgentComboBoxAction.AgentItem>,
    private val agentNotifier: AgentNotifier? = null,
    var onSubmit: (String) -> Unit = {},
    var onAgentChanged: (AgentComboBoxAction.AgentItem?) -> Unit = {},
    var onModelChanged: (ModelComboBoxAction.ModelItem) -> Unit = {},
    var onPlanChanged: (PlanComboBoxAction.PlanItem) -> Unit = {}
) : BorderLayoutPanel() {

    private var isSessionConnected = false
    private var isBusy = false

    private val userInputTextArea = JBTextArea().apply {
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        emptyText.text = "Type your message..."
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    submit()
                }
            }
        })
    }

    private val agentComboBoxAction = AgentComboBoxAction(
        availableAgents = agentItems,
        onAgentSelected = { onAgentChanged(it) },
        agentNotifier = agentNotifier
    )
    private val agentComboBox = agentComboBoxAction.createCustomComponent(
        agentComboBoxAction.templatePresentation, "UserInputPanel"
    ).apply {
        isOpaque = false
        border = null
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        minimumSize = java.awt.Dimension(100, 24)
    }

    private val planComboBoxAction = PlanComboBoxAction(
        project = project,
        onPlanSelected = onPlanChanged,
        agentNotifier = agentNotifier
    )
    private val planComboBox = planComboBoxAction.createCustomComponent(
        planComboBoxAction.templatePresentation, "UserInputPanel"
    ).apply {
        isOpaque = false
        border = null
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        minimumSize = java.awt.Dimension(100, 24)
    }

    private val modelComboBoxAction = ModelComboBoxAction(
        project = project,
        onModelSelected = onModelChanged,
        agentNotifier = agentNotifier
    )
    private val modelComboBox = modelComboBoxAction.createCustomComponent(
        modelComboBoxAction.templatePresentation, "UserInputPanel"
    ).apply {
        isOpaque = false
        border = null
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        minimumSize = java.awt.Dimension(100, 24)
    }

    var sendButton = JButton("Send").apply {
        icon = AllIcons.Actions.Execute
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        isOpaque = false
        addActionListener {
            submit()
        }
    }

    var bottom = panel {
        row {
            cell(
                agentComboBox
            ).align(AlignX.FILL).focused()
            cell(
                planComboBox
            ).align(AlignX.FILL).focused()
            cell(
                modelComboBox
            ).align(AlignX.FILL).focused()
            cell(
                sendButton
            ).align(AlignX.RIGHT).focused()
        }
    }.apply {
        isOpaque = false
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }


    init {
        isOpaque = false
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        border = JBUI.Borders.empty(4)
        addToCenter(userInputTextArea)
        addToBottom(bottom)
    }

    fun setBusy(state: ToolWindowComposerState) {
        isBusy = state != ToolWindowComposerState.IDLE
        sendButton.isEnabled = !isBusy
        agentComboBox.isEnabled = !isBusy
        planComboBox.isEnabled = isSessionConnected && !isBusy
        modelComboBox.isEnabled = isSessionConnected && !isBusy
        userInputTextArea.isEnabled = true
    }

    fun setSessionConnected(connected: Boolean) {
        isSessionConnected = connected
        planComboBox.isEnabled = connected && !isBusy
        modelComboBox.isEnabled = connected && !isBusy
    }

    fun updateModes(modes: List<SessionMode>, currentModeId: String?) {
        planComboBoxAction.updateModes(modes)
        planComboBoxAction.setSelectedById(currentModeId)
        planComboBox.isEnabled = isSessionConnected && !isBusy && modes.isNotEmpty()
    }

    @OptIn(UnstableApi::class)
    fun updateModels(models: List<ModelInfo>, currentModelId: String?) {
        modelComboBoxAction.updateModels(models)
        modelComboBoxAction.setSelectedById(currentModelId)
        modelComboBox.isEnabled = isSessionConnected && !isBusy && models.isNotEmpty()
    }

    fun clearSessionSelectors() {
        planComboBoxAction.clearModes()
        modelComboBoxAction.clearModels()
        planComboBox.isEnabled = false
        modelComboBox.isEnabled = false
    }

    fun updateCommandHint(hasCommands: Boolean) {
        userInputTextArea.emptyText.text = if (hasCommands) {
            "Type your message... (/ for commands)"
        } else {
            "Type your message..."
        }
    }

    fun clearInput() {
        userInputTextArea.text = ""
    }

    fun selectedAgent(): AgentRegistry.AgentDefinition? =
        agentComboBoxAction.getSelectedAgent()?.agentDefinition

    private fun submit() {
        val text = userInputTextArea.text.trim()
        if (text.isEmpty()) {
            return
        }
        onSubmit(text)
        clearInput()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val area = Area(Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
            val roundedRect = RoundRectangle2D.Float(
                0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f
            )
            area.intersect(Area(roundedRect))

            g2.clip = area
            g2.color = background
            g2.fill(area)
            super.paintComponent(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor.namedColor("Component.borderColor", JBColor(0xC9C9C9, 0x5E6068))

            // 如果输入框聚焦，绘制高亮边框
            if (userInputTextArea.isFocusOwner) {
                g2.color = JBUI.CurrentTheme.Focus.defaultButtonColor()
                g2.stroke = BasicStroke(1.5f)
            }

            g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
        } finally {
            g2.dispose()
        }
    }

}
