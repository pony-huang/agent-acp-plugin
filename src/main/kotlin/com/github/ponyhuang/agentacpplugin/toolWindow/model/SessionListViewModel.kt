package com.github.ponyhuang.agentacpplugin.toolWindow.model

import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot

data class SessionListViewModel(
    val selectedSessionId: String?,
    val items: List<SessionListItemViewModel>,
    val availableCommands: List<String>,
    val configOptions: List<String>,
    val bannerText: String?,
    val composerEnabled: Boolean,
) {
    companion object {
        fun fromSnapshots(snapshots: Map<String, SessionViewSnapshot>, selectedSessionId: String?): SessionListViewModel {
            val selectedSnapshot = selectedSessionId?.let(snapshots::get) ?: snapshots.values.firstOrNull()
            return SessionListViewModel(
                selectedSessionId = selectedSessionId,
                items = snapshots.values.sortedBy { it.headerState.title }.map { snapshot ->
                    SessionListItemViewModel.from(snapshot, snapshot.sessionId == selectedSessionId)
                },
                availableCommands = selectedSnapshot?.availableCommands.orEmpty(),
                configOptions = selectedSnapshot?.configOptions.orEmpty(),
                bannerText = selectedSnapshot?.bannerState?.text,
                composerEnabled = selectedSnapshot?.composerEnabled ?: false,
            )
        }
    }
}
