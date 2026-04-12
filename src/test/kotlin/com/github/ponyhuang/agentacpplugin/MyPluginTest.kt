package com.github.ponyhuang.agentacpplugin

import org.junit.Assert.assertTrue
import org.junit.Test

class MyPluginTest {
    @Test
    fun testBundleContainsToolWindowTitleKey() {
        val text = MyBundle.message("toolWindow.title")
        assertTrue(text.contains("ACP") || text.contains("agent"))
    }
}
