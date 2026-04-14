package com.github.ponyhuang.agentacpplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project


@Service(Service.Level.PROJECT)
class AcpProjectService private constructor(
    val project: Project,
) : Disposable {

    private val logger = Logger.getInstance(AcpProjectService::class.java)

    fun connect(vararg command: String) {
    }

    override fun dispose() {
        TODO("Not yet implemented")
    }

}