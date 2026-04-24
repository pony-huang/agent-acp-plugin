package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowComposerState
import com.github.ponyhuang.agentacpplugin.toolwindow.action.ModelComboBoxAction
import com.github.ponyhuang.agentacpplugin.toolwindow.action.PlanComboBoxAction
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JComponent
import javax.swing.SwingUtilities

class AcpUserInputPanelTest : BasePlatformTestCase() {

    fun testCommandPopupTriggerRulesMatchSlashPrefixBehavior() {
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())
        panel.updateCommands(
            listOf(
                AcpUserInputPanel.SessionCommandItem("help", "Show help"),
                AcpUserInputPanel.SessionCommandItem("reset", "Reset session")
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
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())
        panel.updateCommands(
            listOf(
                AcpUserInputPanel.SessionCommandItem("help", "Show available commands"),
                AcpUserInputPanel.SessionCommandItem("review", "Review current diff"),
                AcpUserInputPanel.SessionCommandItem("reset", "Clear the conversation")
            )
        )

        assertEquals(listOf("help"), panel.filterCommands("/hel").map { it.name })
        assertEquals(listOf("review"), panel.filterCommands("/diff").map { it.name })
        assertEquals(listOf("help", "review", "reset"), panel.filterCommands("/").map { it.name })
        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testConnectedSessionEnablesSessionSelectorsAndActionButtons() {
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())
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

        assertFalse(readComponent(panel, "agentComboBox").isEnabled)
        assertTrue(readComponent(panel, "planComboBox").isEnabled)
        assertTrue(readComponent(panel, "modelComboBox").isEnabled)
        assertTrue(readComponent(panel, "connectionButton").isEnabled)
        assertTrue(readComponent(panel, "sendButton").isEnabled)
        assertEquals("Disconnect", readButton(panel, "connectionButton").text)

        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testBusyStateDisablesSelectorsAndActionButtons() {
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())
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
        assertTrue(readComponent(panel, "connectionButton").isEnabled)
        assertEquals("Interrupt", readButton(panel, "connectionButton").text)
        assertFalse(readComponent(panel, "sendButton").isEnabled)

        panel.dispose()
    }

    fun testBusyConnectedStateUsesInterruptActionTooltip() {
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())
        panel.setSessionConnected(true)
        panel.setBusy(ToolWindowComposerState.SENDING)

        val connectionButton = readButton(panel, "connectionButton")

        assertEquals("Interrupt", connectionButton.text)
        assertEquals("Interrupt the current ACP prompt", connectionButton.toolTipText)
        assertTrue(connectionButton.isEnabled)

        panel.dispose()
    }

    fun testDisconnectedSessionKeepsSendDisabledAndShowsConnectAction() {
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())

        assertFalse(readComponent(panel, "sendButton").isEnabled)
        assertEquals("Connect", readButton(panel, "connectionButton").text)

        panel.dispose()
    }

    fun testSendActionIsSeparatedFromSessionControlsRow() {
        val panel = AcpUserInputPanel(project = project, agentItems = emptyList())

        val sessionControlsRow = readComponent(panel, "sessionControlsRow")
        val submitRow = readComponent(panel, "submitRow")

        assertTrue(SwingUtilities.isDescendingFrom(readButton(panel, "connectionButton"), sessionControlsRow))
        assertTrue(SwingUtilities.isDescendingFrom(readButton(panel, "sendButton"), submitRow))
        assertFalse(SwingUtilities.isDescendingFrom(readButton(panel, "sendButton"), sessionControlsRow))

        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testPlanSelectionUsesLatestCallback() {
        var initialCallbackCount = 0
        var latestSelectedPlanId: String? = null
        val panel = AcpUserInputPanel(
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
        val panel = AcpUserInputPanel(
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

    private fun readComponent(panel: AcpUserInputPanel, fieldName: String): JComponent {
        return panel.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(panel) as JComponent
    }

    private fun readButton(panel: AcpUserInputPanel, fieldName: String) =
        readComponent(panel, fieldName) as javax.swing.JButton

    private inline fun <reified T : ComboBoxAction> invokeComboBoxSelection(
        panel: AcpUserInputPanel,
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
