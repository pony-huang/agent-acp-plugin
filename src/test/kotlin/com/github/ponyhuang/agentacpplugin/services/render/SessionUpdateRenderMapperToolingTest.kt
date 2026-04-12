package com.github.ponyhuang.agentacpplugin.services.render

import com.agentclientprotocol.model.Cost
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUpdateRenderMapperToolingTest {
    private val mapper = SessionUpdateRenderMapper()

    @Test
    fun testToolPlanModeAndUsageUpdatesProduceNonBodyIntents() {
        val toolIntents = mapper.map(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("tool-1"),
                title = "shell",
                status = ToolCallStatus.IN_PROGRESS,
            ),
        )
        val planIntents = mapper.map(
            SessionUpdate.PlanUpdate(
                entries = listOf(
                    PlanEntry("Investigate", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS),
                ),
            ),
        )
        val modeIntents = mapper.map(SessionUpdate.CurrentModeUpdate(SessionModeId("review")))
        val usageIntents = mapper.map(SessionUpdate.UsageUpdate(used = 10, size = 20, cost = Cost(0.1, "USD")))

        assertTrue(toolIntents.any { it is RenderIntent.UpsertToolCall })
        assertTrue(planIntents.any { it is RenderIntent.AppendTimeline && it.title == "Plan" })
        assertTrue(modeIntents.any { it is RenderIntent.UpdateCurrentMode })
        assertTrue(usageIntents.any { it is RenderIntent.UpdateUsage })
    }
}
