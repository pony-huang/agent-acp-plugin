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
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
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
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
) : BorderLayoutPanel(), Disposable {

    data class SessionCommandItem(
        val name: String,
        val description: String,
        val hint: String? = null
    )

    private var isSessionConnected = false
    private var isBusy = false
    private var availableCommands: List<SessionCommandItem> = emptyList()
    private var filteredCommands: List<SessionCommandItem> = emptyList()
    private var commandPopup: JBPopup? = null

    private val commandListModel = DefaultListModel<SessionCommandItem>()
    private val commandList = JBList(commandListModel).apply {
        visibleRowCount = 8
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<SessionCommandItem>() {
            override fun customizeCellRenderer(
                list: JList<out SessionCommandItem>,
                value: SessionCommandItem?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) {
                    return
                }
                append("/${value.name}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val suffix = value.description.ifBlank { value.hint.orEmpty() }.trim()
                if (suffix.isNotEmpty()) {
                    append("  $suffix", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        addListSelectionListener {
            repaint()
        }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 1) {
                    applySelectedCommand()
                }
            }
        })
    }

    private val userInputTextArea = JBTextArea().apply {
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
        emptyText.text = "Type your message..."
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleCommandPopupRefresh()
            override fun removeUpdate(e: DocumentEvent) = scheduleCommandPopupRefresh()
            override fun changedUpdate(e: DocumentEvent) = scheduleCommandPopupRefresh()
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (handleCommandPopupKeyEvent(e)) {
                    return
                }
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
        onPlanSelected = { onPlanChanged(it) },
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
        onModelSelected = { onModelChanged(it) },
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
        if (isBusy) {
            hideCommandPopup()
        }
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

    fun updateCommands(commands: List<SessionCommandItem>) {
        availableCommands = commands
        updateCommandHint(commands.isNotEmpty())
        refreshCommandPopup()
    }

    fun updateCommandHint(hasCommands: Boolean) {
        userInputTextArea.emptyText.text = if (hasCommands) {
            "Type your message... (/ for commands)"
        } else {
            "Type your message..."
        }
        if (!hasCommands) {
            hideCommandPopup()
        }
    }

    fun clearInput() {
        userInputTextArea.text = ""
    }

    fun selectedAgent(): AgentRegistry.AgentDefinition? =
        agentComboBoxAction.getSelectedAgent()?.agentDefinition

    private fun submit() {
        if (isBusy) {
            return
        }
        val text = userInputTextArea.text.trim()
        if (text.isEmpty()) {
            return
        }
        hideCommandPopup()
        onSubmit(text)
        clearInput()
    }

    internal fun shouldShowCommandPopup(text: String): Boolean {
        if (availableCommands.isEmpty() || isBusy) {
            return false
        }

        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) {
            return false
        }

        return !trimmed.contains(' ')
    }

    internal fun filterCommands(text: String): List<SessionCommandItem> {
        val filter = text.trimStart().removePrefix("/").lowercase()
        if (filter.isBlank()) {
            return availableCommands
        }

        return availableCommands.filter { command ->
            command.name.lowercase().startsWith(filter) ||
                    command.description.lowercase().contains(filter)
        }
    }

    private fun scheduleCommandPopupRefresh() {
        SwingUtilities.invokeLater {
            refreshCommandPopup()
        }
    }

    private fun refreshCommandPopup() {
        val text = userInputTextArea.text
        if (!shouldShowCommandPopup(text)) {
            hideCommandPopup()
            return
        }

        filteredCommands = filterCommands(text)
        if (filteredCommands.isEmpty()) {
            hideCommandPopup()
            return
        }

        commandListModel.removeAllElements()
        filteredCommands.forEach(commandListModel::addElement)
        if (commandList.selectedIndex !in filteredCommands.indices) {
            commandList.selectedIndex = 0
        }

        if (commandPopup?.isVisible != true) {
            showCommandPopup()
        } else {
            commandList.revalidate()
            commandList.repaint()
        }
    }

    private fun showCommandPopup() {
        hideCommandPopup()

        val popupContent = JBScrollPane(commandList).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(JBUI.scale(420), JBUI.scale(220))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        commandPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, commandList)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .createPopup()

        try {
            val caretBounds = userInputTextArea.modelToView2D(userInputTextArea.caretPosition).bounds
            val point = java.awt.Point(caretBounds.x, caretBounds.y + caretBounds.height)
            SwingUtilities.convertPointToScreen(point, userInputTextArea)
            commandPopup?.show(RelativePoint(point))
        } catch (_: Exception) {
            commandPopup?.showInBestPositionFor(DataManager.getInstance().getDataContext(userInputTextArea))
        }
    }

    private fun hideCommandPopup() {
        commandPopup?.cancel()
        commandPopup = null
    }

    private fun handleCommandPopupKeyEvent(e: KeyEvent): Boolean {
        if (commandPopup?.isVisible != true || filteredCommands.isEmpty()) {
            return false
        }

        when (e.keyCode) {
            KeyEvent.VK_DOWN -> {
                e.consume()
                val nextIndex = (commandList.selectedIndex + 1).mod(filteredCommands.size)
                commandList.selectedIndex = nextIndex
                commandList.ensureIndexIsVisible(nextIndex)
                return true
            }

            KeyEvent.VK_UP -> {
                e.consume()
                val nextIndex = if (commandList.selectedIndex <= 0) {
                    filteredCommands.lastIndex
                } else {
                    commandList.selectedIndex - 1
                }
                commandList.selectedIndex = nextIndex
                commandList.ensureIndexIsVisible(nextIndex)
                return true
            }

            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                e.consume()
                applySelectedCommand()
                return true
            }

            KeyEvent.VK_ESCAPE -> {
                e.consume()
                hideCommandPopup()
                return true
            }
        }

        return false
    }

    private fun applySelectedCommand() {
        val selected = commandList.selectedValue ?: filteredCommands.firstOrNull() ?: return
        userInputTextArea.text = "/${selected.name} "
        userInputTextArea.caretPosition = userInputTextArea.text.length
        hideCommandPopup()
        userInputTextArea.requestFocusInWindow()
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

    override fun removeNotify() {
        hideCommandPopup()
        super.removeNotify()
    }

    override fun dispose() {
        hideCommandPopup()
    }
}
