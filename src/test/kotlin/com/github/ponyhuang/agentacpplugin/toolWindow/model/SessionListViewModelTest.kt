package com.github.ponyhuang.agentacpplugin.toolWindow.model

import com.github.ponyhuang.agentacpplugin.services.render.BannerState
import com.github.ponyhuang.agentacpplugin.services.render.SessionHeaderState
import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import kotlin.io.path.readText

class SessionListViewModelTest {
    @Test
    fun testSelectedSessionRestoresMetaAndBanner() {
        val banner = Paths.get("src/test/testData/toolWindow/session-switch-restore.json").readText()
        val viewModel = SessionListViewModel.fromSnapshots(
            snapshots = mapOf(
                "session-1" to SessionViewSnapshot(
                    sessionId = "session-1",
                    headerState = SessionHeaderState("Alpha", "CONNECTED", "STREAMING"),
                    visibleTimeline = emptyList(),
                    composerEnabled = true,
                    bannerState = BannerState(banner, false),
                    availableCommands = listOf("/review"),
                    configOptions = listOf("model=gpt"),
                ),
            ),
            selectedSessionId = "session-1",
        )
        assertEquals("/review", viewModel.availableCommands.single())
        assertTrue(viewModel.bannerText!!.contains("restore"))
    }
}
