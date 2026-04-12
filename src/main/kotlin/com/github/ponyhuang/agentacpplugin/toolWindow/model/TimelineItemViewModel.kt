package com.github.ponyhuang.agentacpplugin.toolWindow.model

import com.github.ponyhuang.agentacpplugin.services.render.TimelineItem

data class TimelineItemViewModel(
    val title: String,
    val body: String,
    val type: String,
    val state: String,
    val secondaryText: String = "",
) {
    companion object {
        fun from(item: TimelineItem): TimelineItemViewModel {
            return TimelineItemViewModel(
                title = item.title ?: item.itemType.name,
                body = item.textContent,
                type = item.itemType.name,
                state = item.displayState.name,
                secondaryText = item.metadata.entries.joinToString { "${it.key}: ${it.value}" },
            )
        }
    }
}
