package com.github.ponyhuang.agentacpplugin.services

import com.github.ponyhuang.agentacpplugin.services.render.SessionViewSnapshot
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class MyProjectService(project: Project) {
    private val delegate = project.service<AcpProjectService>()

    fun connect(commandLine: String) = delegate.connect(commandLine)

    fun submitPrompt(prompt: String) = delegate.submitPrompt(prompt)

    fun selectSession(sessionId: String) = delegate.selectSession(sessionId)

    fun snapshots(): Map<String, SessionViewSnapshot> = delegate.snapshots()
}
