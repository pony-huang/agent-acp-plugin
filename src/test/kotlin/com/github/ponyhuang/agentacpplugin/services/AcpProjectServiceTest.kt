package com.github.ponyhuang.agentacpplugin.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class AcpProjectServiceTest : BasePlatformTestCase() {
    fun testAcpProjectService() {
        runBlocking {
            val service = AcpAgentClient(
                project = project,
                coroutineScope = CoroutineScope(Dispatchers.Unconfined),
                cmd = listOf("npx.cmd", "@agentclientprotocol/claude-agent-acp"),
                envs = emptyList<String>()
            )
            service.connect()
            var message = service.newSession()
            print(Json.encodeToString(message))
        }
    }

}
