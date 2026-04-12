package com.github.ponyhuang.agentacpplugin.services.acp

import org.junit.Assert.assertEquals
import org.junit.Test

class AcpClientFacadeTest {
    @Test
    fun testTokenizeCommandLineWithQuotedArgs() {
        val launcher = AcpAgentProcessLauncher()
        assertEquals(
            listOf("gemini", "--experimental-acp", "--label", "hello world"),
            launcher.tokenize("gemini --experimental-acp --label \"hello world\""),
        )
    }
}
