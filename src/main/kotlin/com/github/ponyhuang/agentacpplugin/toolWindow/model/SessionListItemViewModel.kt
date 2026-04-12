package com.github.ponyhuang.agentacpplugin.toolWindow.model

import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot

data class SessionListItemViewModel(
    val sessionId: String,
    val title: String,
    val statusText: String,
    val isSelected: Boolean,
    val hasUnread: Boolean,
) {
    override fun toString(): String = buildString {
        append(title)
        append(" [")
        append(statusText)
        append(']')
        if (hasUnread) {
            append(" *")
        }
    }

    companion object {
        fun from(snapshot: SessionViewSnapshot, selected: Boolean): SessionListItemViewModel {
            return SessionListItemViewModel(
                sessionId = snapshot.sessionId,
                title = snapshot.headerState.title,
                statusText = snapshot.headerState.sessionStatus,
                isSelected = selected,
                hasUnread = snapshot.bannerState != null && !selected,
            )
        }
    }
}
