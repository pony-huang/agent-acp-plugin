package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.SessionMode
import com.agentclientprotocol.model.SessionModeId
import com.github.ponyhuang.agentacpplugin.toolwindow.ToolWindowComposerState
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
    fun testConnectedSessionEnablesSelectorsAndSendButton() {
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

        assertTrue(readComponent(panel, "agentComboBox").isEnabled)
        assertTrue(readComponent(panel, "planComboBox").isEnabled)
        assertTrue(readComponent(panel, "modelComboBox").isEnabled)
        assertTrue(readComponent(panel, "sendButton").isEnabled)

        panel.dispose()
    }

    @OptIn(UnstableApi::class)
    fun testBusyStateDisablesSelectorsAndSendButton() {
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
        assertFalse(readComponent(panel, "sendButton").isEnabled)

        panel.dispose()
    }

    private fun readComponent(panel: AcpUserInputPanel, fieldName: String): JComponent {
        return panel.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(panel) as JComponent
    }

    private fun flushEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            return
        }
        SwingUtilities.invokeAndWait {}
    }
}
