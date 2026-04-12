package com.github.ponyhuang.agentacpplugin.services.render

data class SessionHeaderState(
    val title: String,
    val connectionStatus: String,
    val sessionStatus: String,
    val currentMode: String? = null,
    val usageSummary: String? = null,
)

data class BannerState(
    val text: String,
    val isError: Boolean,
)

data class SessionViewSnapshot(
    val sessionId: String,
    val headerState: SessionHeaderState,
    val visibleTimeline: List<TimelineItem>,
    val composerEnabled: Boolean,
    val bannerState: BannerState? = null,
    val availableCommands: List<String> = emptyList(),
    val configOptions: List<String> = emptyList(),
)
