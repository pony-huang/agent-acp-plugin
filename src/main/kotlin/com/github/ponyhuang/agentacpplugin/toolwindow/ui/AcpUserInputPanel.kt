package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import com.github.ponyhuang.agentacpplugin.MyBundle
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import javax.swing.*
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
    var onConnectionToggle: () -> Unit = {},
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
    private var currentState = ToolWindowComposerState.IDLE
    private var availableCommands: List<SessionCommandItem> = emptyList()
    private var filteredCommands: List<SessionCommandItem> = emptyList()
    private var commandPopup: JBPopup? = null
    private var connectionAnimationIcon: AnimatedIcon? = null

    private val commandListModel = DefaultListModel<SessionCommandItem>()
    private val commandList = JBList(commandListModel).apply {
        visibleRowCount = 8
        selectionMode = ListSelectionModel.SINGLE_SELECTION
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
        emptyText.text = MyBundle.message("input.placeholder")
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
        onAgentSelected = {
            onAgentChanged(it)
            updateControlStates()
        },
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

    var sendButton = JButton(MyBundle.message("input.send")).apply {
        icon = AllIcons.Actions.Execute
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        isOpaque = false
        addActionListener {
            submit()
        }
    }

    var connectionButton = JButton(MyBundle.message("input.connect")).apply {
        icon = AllIcons.Actions.Execute
        isContentAreaFilled = false
        border = JBUI.Borders.empty()
        isOpaque = false
        addActionListener {
            onConnectionToggle()
        }
    }

    private val sessionControlsRow = panel {
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
                connectionButton
            ).align(AlignX.RIGHT).focused()
            cell(
                sendButton
            ).align(AlignX.RIGHT).focused()
        }.resizableRow()
    }.apply {
        isOpaque = false
        background = EditorColorsManager.getInstance().globalScheme.defaultBackground
    }

    var bottom = panel {
        row {
            cell(sessionControlsRow)
                .align(AlignX.FILL)
                .resizableColumn()
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
        updateControlStates()
    }

    fun setBusy(state: ToolWindowComposerState) {
        val previousState = currentState
        currentState = state
        isBusy = state != ToolWindowComposerState.IDLE
        updateControlStates()
        userInputTextArea.isEnabled = true
        if (isBusy) {
            hideCommandPopup()
        }

        // Handle connection animation
        when (state) {
            ToolWindowComposerState.CONNECTING -> {
                if (previousState != ToolWindowComposerState.CONNECTING) {
                    connectionAnimationIcon = AnimatedIcon().apply {
                        startAnimation()
                    }
                    connectionButton.icon = connectionAnimationIcon
                }
            }

            else -> {
                connectionAnimationIcon?.stopAnimation()
                connectionAnimationIcon = null
                // Restore appropriate icon based on connection state
                connectionButton.icon = if (isSessionConnected) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            }
        }
    }

    fun setSessionConnected(connected: Boolean) {
        isSessionConnected = connected
        updateControlStates()
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
        updateControlStates()
    }

    fun updateCommands(commands: List<SessionCommandItem>) {
        availableCommands = commands
        updateCommandHint(commands.isNotEmpty())
        refreshCommandPopup()
    }

    fun updateCommandHint(hasCommands: Boolean) {
        userInputTextArea.emptyText.text = if (hasCommands) {
            MyBundle.message("input.placeholderWithCommands")
        } else {
            MyBundle.message("input.placeholder")
        }
        if (!hasCommands) {
            hideCommandPopup()
        }
    }

    fun clearInput() {
        userInputTextArea.text = ""
    }

    fun selectedAgent(): AgentRegistry.InstalledAgent? =
        agentComboBoxAction.getSelectedAgent()?.agentDefinition

    fun updateAgents(agentItems: List<AgentComboBoxAction.AgentItem>) {
        val previousAgentId = agentComboBoxAction.getSelectedAgent()?.id
        agentComboBoxAction.updateAgents(agentItems)
        if (agentComboBoxAction.getSelectedAgent()?.id != previousAgentId) {
            onAgentChanged(agentComboBoxAction.getSelectedAgent())
        }
        updateControlStates()
        repaint()
    }

    private fun updateControlStates() {
        val hasSelectedAgent = agentComboBoxAction.getSelectedAgent() != null
        val canInterrupt = isSessionConnected && isBusy
        agentComboBox.isEnabled = !isBusy && !isSessionConnected
        planComboBox.isEnabled = isSessionConnected && !isBusy && planComboBoxAction.getSelectedPlan() != null
        modelComboBox.isEnabled = isSessionConnected && !isBusy && modelComboBoxAction.getSelectedModel() != null
        sendButton.isEnabled = isSessionConnected && !isBusy
        connectionButton.isEnabled = canInterrupt || !isBusy && (isSessionConnected || hasSelectedAgent)
        connectionButton.text = when {
            canInterrupt -> MyBundle.message("input.interrupt")
            isSessionConnected -> MyBundle.message("input.disconnect")
            else -> MyBundle.message("input.connect")
        }
        connectionButton.icon = if (isSessionConnected) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        connectionButton.toolTipText = when {
            canInterrupt -> MyBundle.message("input.tooltipInterrupt")
            isSessionConnected -> MyBundle.message("input.tooltipDisconnect")
            else -> MyBundle.message("input.tooltipConnect")
        }
    }

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

    private inner class AnimatedIcon : javax.swing.Icon {
        private val animatedIcons = arrayOf(
            AllIcons.Process.Step_1,
            AllIcons.Process.Step_2,
            AllIcons.Process.Step_3,
            AllIcons.Process.Step_4,
            AllIcons.Process.Step_5,
            AllIcons.Process.Step_6,
            AllIcons.Process.Step_7,
            AllIcons.Process.Step_8
        )
        private val animationTimer = Timer(60, null)
        private var animationFrame = 0
        private var isAnimating = false

        init {
            animationTimer.addActionListener {
                animationFrame = (animationFrame + 1) % animatedIcons.size
                repaint()
            }
            animationTimer.isRepeats = true
        }

        fun startAnimation() {
            animationFrame = 0
            isAnimating = true
            animationTimer.start()
        }

        fun stopAnimation() {
            isAnimating = false
            animationTimer.stop()
        }

        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            if (animationFrame in animatedIcons.indices) {
                animatedIcons[animationFrame].paintIcon(c, g, x, y)
            }
        }

        override fun getIconWidth(): Int = AllIcons.Process.Step_1.iconWidth

        override fun getIconHeight(): Int = AllIcons.Process.Step_1.iconHeight
    }
}
