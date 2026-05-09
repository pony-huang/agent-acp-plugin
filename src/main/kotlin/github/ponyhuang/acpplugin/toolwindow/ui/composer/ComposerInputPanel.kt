package github.ponyhuang.acpplugin.toolwindow.ui.composer

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import github.ponyhuang.acpplugin.MyBundle
import github.ponyhuang.acpplugin.services.AgentNotifier
import github.ponyhuang.acpplugin.services.AgentRegistry
import github.ponyhuang.acpplugin.toolwindow.ToolWindowComposerState
import github.ponyhuang.acpplugin.toolwindow.action.AgentComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.action.ModelComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.action.PlanComboBoxAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * @author: pony
 */
class ComposerInputPanel(
    val project: Project,
    agentItems: List<AgentComboBoxAction.AgentItem>,
    private val agentNotifier: AgentNotifier? = null,
    var onSubmit: (String) -> Unit = {},
    var onConnectionToggle: () -> Unit = {},
    var onAgentChanged: (AgentComboBoxAction.AgentItem?) -> Unit = {},
    var onModelChanged: (ModelComboBoxAction.ModelItem) -> Unit = {},
    var onPlanChanged: (PlanComboBoxAction.PlanItem) -> Unit = {}
) : BorderLayoutPanel(), Disposable {
    private var currentState: ToolWindowComposerState = ToolWindowComposerState.IDLE
    private val isBusy: Boolean get() = currentState != ToolWindowComposerState.IDLE

    private val commandController: ComposerCommandController = ComposerCommandController()

    private val userInputTextArea: JBTextArea = JBTextArea().apply {
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        emptyText.text = MyBundle.message("input.placeholder")
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = commandPopup.scheduleRefresh()
            override fun removeUpdate(e: DocumentEvent) = commandPopup.scheduleRefresh()
            override fun changedUpdate(e: DocumentEvent) = commandPopup.scheduleRefresh()
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (commandPopup.handleKeyEvent(e)) {
                    return
                }
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    submit()
                }
            }
        })
    }

    private val sessionControls: ComposerSessionControls = ComposerSessionControls(
        project = project,
        agentItems = agentItems,
        agentNotifier = agentNotifier,
        onAgentChanged = { onAgentChanged(it) },
        onModelChanged = { onModelChanged(it) },
        onPlanChanged = { onPlanChanged(it) },
        onSubmit = { submit() },
        onConnectionToggle = { onConnectionToggle() }
    )

    private val commandPopup: ComposerCommandPopup = ComposerCommandPopup(
        userInputTextArea = userInputTextArea,
        commandController = commandController,
        isBusy = { isBusy }
    )

    val sendButton: JButton get() = sessionControls.sendButton
    val connectionButton: JButton get() = sessionControls.connectionButton
    val agentComboBoxAction: AgentComboBoxAction get() = sessionControls.agentComboBoxAction

    init {
        isOpaque = false
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        border = JBUI.Borders.empty(4)
        addToCenter(userInputTextArea)
        addToBottom(sessionControls.component)
    }

    fun setBusy(state: ToolWindowComposerState) {
        currentState = state
        userInputTextArea.isEnabled = true
        if (isBusy) {
            commandPopup.hide()
        }
        sessionControls.setBusy(state)
    }

    fun setSessionConnected(connected: Boolean) {
        sessionControls.setSessionConnected(connected)
    }

    fun updateModes(modes: List<SessionMode>, currentModeId: String?) {
        sessionControls.updateModes(modes, currentModeId)
    }

    @OptIn(UnstableApi::class)
    fun updateModels(models: List<ModelInfo>, currentModelId: String?) {
        sessionControls.updateModels(models, currentModelId)
    }

    fun clearSessionSelectors() {
        sessionControls.clearSessionSelectors()
    }

    fun updateCommands(commands: List<ComposerCommandItem>) {
        commandController.updateCommands(commands)
        updateCommandHint(commands.isNotEmpty())
        commandPopup.refresh()
    }

    fun updateCommandHint(hasCommands: Boolean) {
        userInputTextArea.emptyText.text = if (hasCommands) {
            MyBundle.message("input.placeholderWithCommands")
        } else {
            MyBundle.message("input.placeholder")
        }
        if (!hasCommands) {
            commandPopup.hide()
        }
    }

    fun clearInput() {
        userInputTextArea.text = ""
    }

    fun selectedAgent(): AgentRegistry.InstalledAgent? =
        sessionControls.selectedAgent()

    fun updateAgents(agentItems: List<AgentComboBoxAction.AgentItem>) {
        sessionControls.updateAgents(agentItems)
        repaint()
    }

    internal fun shouldShowCommandPopup(text: String): Boolean {
        return commandPopup.shouldShow(text)
    }

    internal fun filterCommands(text: String): List<ComposerCommandItem> {
        return commandController.filterCommands(text)
    }

    private fun submit() {
        if (isBusy) return
        val text = userInputTextArea.text.trim()
        if (text.isEmpty()) return
        commandPopup.hide()
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
            val isFocused = userInputTextArea.isFocusOwner
            val strokeWidth = if (isFocused) 1.5f else 1f
            val inset = strokeWidth / 2f
            g2.color = if (isFocused) {
                JBUI.CurrentTheme.Focus.defaultButtonColor()
            } else {
                JBColor.namedColor("Component.borderColor", JBColor(0xC9C9C9, 0x5E6068))
            }
            g2.stroke = BasicStroke(strokeWidth)
            g2.draw(
                RoundRectangle2D.Float(
                    inset,
                    inset,
                    width - strokeWidth,
                    height - strokeWidth,
                    16f - strokeWidth,
                    16f - strokeWidth
                )
            )
        } finally {
            g2.dispose()
        }
    }

    override fun removeNotify() {
        commandPopup.hide()
        super.removeNotify()
    }

    override fun dispose() {
        commandPopup.dispose()
        sessionControls.dispose()
    }
}
