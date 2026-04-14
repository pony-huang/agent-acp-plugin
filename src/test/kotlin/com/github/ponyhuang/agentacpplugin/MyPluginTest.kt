package com.github.ponyhuang.agentacpplugin

import org.junit.Assert.assertTrue
import org.junit.Test

class MyPluginTest {
    @Test
    fun testBundleContainsProjectServiceKey() {
        val text = MyBundle.message("projectService", "test-project")
        assertTrue(text.contains("test-project"))
    }
}
