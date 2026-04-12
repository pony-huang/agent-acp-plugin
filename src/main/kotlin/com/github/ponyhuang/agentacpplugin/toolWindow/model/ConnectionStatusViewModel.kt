package com.github.ponyhuang.agentacpplugin.toolWindow.model

data class ConnectionStatusViewModel(
    val commandLine: String = "",
    val statusText: String = "DISCONNECTED",
    val canConnect: Boolean = true,
    val errorText: String? = null,
)
