package com.github.ponyhuang.agentacpplugin.toolwindow

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BuiltInAcpAgentRegistryTest : BasePlatformTestCase() {

    fun testRegistryContainsExpectedAgentDefinitions() {
        val names = BuiltInAcpAgentRegistry.agents.map { it.displayName }

        assertContainsElements(
            names,
            "GitHub Copilot",
            "Claude Code",
            "Gemini CLI",
            "Qwen Code",
            "Auggie CLI",
            "Qoder CLI",
            "Codex CLI",
            "OpenCode",
            "OpenClaw",
        )
    }

    fun testAuggieDescriptorIncludesFixedEnv() {
        val descriptor = BuiltInAcpAgentRegistry.agents
            .first { it.displayName == "Auggie CLI" }
            .toDescriptor()

        assertEquals("npx", descriptor.command)
        assertEquals(listOf("@augmentcode/auggie@latest", "--acp"), descriptor.args)
        assertEquals("1", descriptor.env["AUGMENT_DISABLE_AUTO_UPDATE"])
    }

    fun testClaudeDefinitionRequiresAnthropicKey() {
        val claude = BuiltInAcpAgentRegistry.agents.first { it.displayName == "Claude Code" }

        assertEquals(listOf("ANTHROPIC_API_KEY"), claude.requiredEnvKeys)

        if (System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) {
            val error = try {
                claude.toDescriptor()
                error("Expected missing environment variable to fail")
            } catch (t: IllegalStateException) {
                t
            }
            assertEquals(
                "Agent 'Claude Code' requires environment variable 'ANTHROPIC_API_KEY'.",
                error.message,
            )
        }
    }
}
