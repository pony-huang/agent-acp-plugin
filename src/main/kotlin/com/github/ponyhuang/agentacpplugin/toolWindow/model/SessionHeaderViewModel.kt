package com.github.ponyhuang.agentacpplugin.toolWindow.model

import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot

data class SessionHeaderViewModel(
    val title: String,
    val statusText: String,
    val modeText: String,
    val usageText: String,
) {
    companion object {
        fun from(snapshot: SessionViewSnapshot): SessionHeaderViewModel {
            return SessionHeaderViewModel(
                title = snapshot.headerState.title,
                statusText = "${snapshot.headerState.connectionStatus} / ${snapshot.headerState.sessionStatus}",
                modeText = snapshot.headerState.currentMode ?: "default",
                usageText = snapshot.headerState.usageSummary ?: "-",
            )
        }
    }
}
