package github.ponyhuang.acpplugin.toolwindow.ui.input

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import github.ponyhuang.acpplugin.toolwindow.ToolWindowComposerState
import github.ponyhuang.acpplugin.toolwindow.ui.input.selector.ModelComboBoxAction
import github.ponyhuang.acpplugin.toolwindow.ui.input.selector.PlanComboBoxAction
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComponent
import javax.swing.SwingUtilities

class AcpUserInputPanelTest : BasePlatformTestCase() {

    fun testCommandPopupTriggerRulesMatchSlashPrefixBehavior() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())
        panel.updateCommands(
            listOf(
                UserInputPanel.SessionCommandItem("help", "Show help"),
                UserInputPanel.SessionCommandItem("reset", "Reset session")
            )
        )

        assertTrue(panel.shouldShowCommandPopup("/"))
        assertTrue(panel.shouldShowCommandPopup("/he"))
        assertFalse(panel.shouldShowCommandPopup("hello"))
        assertFalse(panel.shouldShowCommandPopup("/help topic"))

        panel.setBusy(ToolWindowComposerState.SENDING)
        assertFalse(panel.shouldShowCommandPopup("/"))
        panel.dispose()
    }

    fun testCommandFilteringMatchesNamePrefixAndDescriptionText() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())
        panel.updateCommands(
            listOf(
                UserInputPanel.SessionCommandItem("help", "Show available commands"),
                UserInputPanel.SessionCommandItem("review", "Review current diff"),
                UserInputPanel.SessionCommandItem("reset", "Clear the conversation")
            )
        )

        assertEquals(listOf("help"), panel.filterCommands("/hel").map { it.name })
        assertEquals(listOf("review"), panel.filterCommands("/diff").map { it.name })
        assertEquals(listOf("help", "review", "reset"), panel.filterCommands("/").map { it.name })
        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testConnectedSessionEnablesSessionSelectorsAndActionButtons() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())
        panel.setSessionConnected(true)
        panel.updateModes(
            listOf(
                SessionMode(
                    id = SessionModeId("default"),
                    name = "Default",
                    description = "Default mode"
                )
            ),
            "default"
        )
        panel.updateModels(
            listOf(
                ModelInfo(
                    modelId = ModelId("gpt-5"),
                    name = "GPT-5",
                    description = "Primary model"
                )
            ),
            "gpt-5"
        )

        flushEdt()

        assertTrue(readComponent(panel, "agentComboBox").isEnabled)
        assertTrue(readComponent(panel, "planComboBox").isEnabled)
        assertTrue(readComponent(panel, "modelComboBox").isEnabled)
        assertFalse(readComponent(panel, "connectionButton").isVisible)
        assertTrue(readComponent(panel, "sendButton").isEnabled)

        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testBusyStateDisablesSelectorsAndActionButtons() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())
        panel.setSessionConnected(true)
        panel.updateModes(
            listOf(
                SessionMode(
                    id = SessionModeId("default"),
                    name = "Default",
                    description = "Default mode"
                )
            ),
            "default"
        )
        panel.updateModels(
            listOf(
                ModelInfo(
                    modelId = ModelId("gpt-5"),
                    name = "GPT-5",
                    description = "Primary model"
                )
            ),
            "gpt-5"
        )
        flushEdt()

        panel.setBusy(ToolWindowComposerState.SENDING)
        flushEdt()

        assertFalse(readComponent(panel, "agentComboBox").isEnabled)
        assertFalse(readComponent(panel, "planComboBox").isEnabled)
        assertFalse(readComponent(panel, "modelComboBox").isEnabled)
        assertFalse(readComponent(panel, "connectionButton").isVisible)
        assertFalse(readComponent(panel, "sendButton").isEnabled)

        panel.dispose()
    }

    fun testConnectionButtonRemainsHiddenDuringBusyConnectedState() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())
        panel.setSessionConnected(true)
        panel.setBusy(ToolWindowComposerState.SENDING)

        val connectionButton = readButton(panel, "connectionButton")

        assertFalse(connectionButton.isVisible)
        assertEquals("Interrupt", connectionButton.text)
        assertEquals("Interrupt the current ACP prompt", connectionButton.toolTipText)

        panel.dispose()
    }

    fun testDisconnectedSessionKeepsSendDisabledAndHidesConnectionAction() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())

        assertFalse(readComponent(panel, "sendButton").isEnabled)
        assertFalse(readComponent(panel, "connectionButton").isVisible)
        assertEquals("Connect", readButton(panel, "connectionButton").text)

        panel.dispose()
    }

    fun testInitialStateHasNoSelectedAgent() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())

        assertNull(panel.selectedAgent())

        panel.dispose()
    }

    fun testConnectedSessionKeepsAgentSelectorEnabledForSwitching() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())
        panel.setSessionConnected(true)

        assertTrue(readComponent(panel, "agentComboBox").isEnabled)

        panel.dispose()
    }

    fun testSendActionSharesSessionControlsRow() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())

        val sessionControlsRow = readComponent(panel, "sessionControlsRow")

        assertFalse(SwingUtilities.isDescendingFrom(readButton(panel, "connectionButton"), sessionControlsRow))
        assertTrue(SwingUtilities.isDescendingFrom(readButton(panel, "sendButton"), sessionControlsRow))

        panel.dispose()
    }

    fun testSessionSelectorsUseCompactHorizontalLayout() {
        val panel = UserInputPanel(project = project, agentItems = emptyList())

        val selectorRow = readComponent(panel, "selectorRow")

        assertInstanceOf(selectorRow.layout, HorizontalLayout::class.java)
        assertTrue(SwingUtilities.isDescendingFrom(readComponent(panel, "agentComboBox"), selectorRow))
        assertTrue(SwingUtilities.isDescendingFrom(readComponent(panel, "planComboBox"), selectorRow))
        assertTrue(SwingUtilities.isDescendingFrom(readComponent(panel, "modelComboBox"), selectorRow))
        assertFalse(SwingUtilities.isDescendingFrom(readButton(panel, "sendButton"), selectorRow))

        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testPlanSelectionUsesLatestCallback() {
        var initialCallbackCount = 0
        var latestSelectedPlanId: String? = null
        val panel = UserInputPanel(
            project = project,
            agentItems = emptyList(),
            onPlanChanged = { initialCallbackCount++ }
        )
        panel.updateModes(
            listOf(
                SessionMode(
                    id = SessionModeId("default"),
                    name = "Default",
                    description = "Default mode"
                ),
                SessionMode(
                    id = SessionModeId("plan"),
                    name = "Plan",
                    description = "Plan mode"
                )
            ),
            "default"
        )
        panel.onPlanChanged = { plan -> latestSelectedPlanId = plan.id }

        invokeComboBoxSelection<PlanComboBoxAction>(panel, "planComboBoxAction", 1)

        assertEquals(0, initialCallbackCount)
        assertEquals("plan", latestSelectedPlanId)
        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testModelSelectionUsesLatestCallback() {
        var initialCallbackCount = 0
        var latestSelectedModelId: String? = null
        val panel = UserInputPanel(
            project = project,
            agentItems = emptyList(),
            onModelChanged = { initialCallbackCount++ }
        )
        panel.updateModels(
            listOf(
                ModelInfo(
                    modelId = ModelId("gpt-5"),
                    name = "GPT-5",
                    description = "Primary model"
                ),
                ModelInfo(
                    modelId = ModelId("gpt-5.4"),
                    name = "GPT-5.4",
                    description = "Updated model"
                )
            ),
            "gpt-5"
        )
        panel.onModelChanged = { model -> latestSelectedModelId = model.id }

        invokeComboBoxSelection<ModelComboBoxAction>(panel, "modelComboBoxAction", 1)

        assertEquals(0, initialCallbackCount)
        assertEquals("gpt-5.4", latestSelectedModelId)
        panel.dispose()
    }

    private fun readComponent(panel: UserInputPanel, fieldName: String): JComponent {
        return panel.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(panel) as JComponent
    }

    private fun readButton(panel: UserInputPanel, fieldName: String) =
        readComponent(panel, fieldName) as javax.swing.JButton

    private inline fun <reified T : ComboBoxAction> invokeComboBoxSelection(
        panel: UserInputPanel,
        fieldName: String,
        index: Int
    ) {
        val action = panel.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(panel) as T
        val componentName = fieldName.removeSuffix("Action").replaceFirstChar { it.lowercase() }
        val component = readComponent(panel, componentName)
        val group = ComboBoxAction::class.java.getDeclaredMethod(
            "createPopupActionGroup",
            JComponent::class.java,
            com.intellij.openapi.actionSystem.DataContext::class.java
        ).apply {
            isAccessible = true
        }.invoke(action, component, SimpleDataContext.EMPTY_CONTEXT) as DefaultActionGroup
        val childAction = group.childActionsOrStubs[index] as AnAction
        val event = AnActionEvent.createFromAnAction(
            childAction,
            null,
            ActionPlaces.UNKNOWN,
            SimpleDataContext.EMPTY_CONTEXT
        )
        childAction.actionPerformed(event)
    }

    private fun flushEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            return
        }
        SwingUtilities.invokeAndWait {}
    }
}
