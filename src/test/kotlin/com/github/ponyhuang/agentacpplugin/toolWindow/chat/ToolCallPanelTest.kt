package com.github.ponyhuang.agentacpplugin.toolWindow.chat

import com.github.ponyhuang.agentacpplugin.toolWindow.model.ToolCallViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Paths
import kotlin.io.path.readText

class ToolCallPanelTest {
    @Test
    fun testToolCallPanelUsesRunningFixtureSummary() {
        val summary = Paths.get("src/test/testData/toolWindow/tool-call-running.json").readText()
        val panel = ToolCallPanel(ToolCallViewModel("shell", "RUNNING", summary))
        assertEquals(2, panel.componentCount)
    }
}
