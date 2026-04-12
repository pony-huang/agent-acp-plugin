package com.github.ponyhuang.agentacpplugin.toolWindow.chat

import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot
import com.github.ponyhuang.agentacpplugin.toolWindow.model.SessionHeaderViewModel
import com.github.ponyhuang.agentacpplugin.toolWindow.model.TimelineItemViewModel

data class ConversationPanelModel(
    val header: SessionHeaderViewModel,
    val timeline: List<TimelineItemViewModel>,
    val bannerText: String?,
    val composerEnabled: Boolean,
) {
    companion object {
        fun from(snapshot: SessionViewSnapshot): ConversationPanelModel {
            return ConversationPanelModel(
                header = SessionHeaderViewModel.from(snapshot),
                timeline = snapshot.visibleTimeline.map(TimelineItemViewModel::from),
                bannerText = snapshot.bannerState?.text,
                composerEnabled = snapshot.composerEnabled,
            )
        }
    }
}
