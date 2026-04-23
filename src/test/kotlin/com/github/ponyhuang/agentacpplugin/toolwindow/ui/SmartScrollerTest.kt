package com.github.ponyhuang.agentacpplugin.toolwindow.ui

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import org.junit.Test

class SmartScrollerTest {

    @Test
    fun keepsFollowingWhenViewportWasAlreadyAtBottom() {
        val state = SmartScrollState()

        assertNull(state.update(value = 90, extent = 10, maximum = 100))
        assertEquals(130, state.update(value = 90, extent = 10, maximum = 140))
    }

    @Test
    fun stopsFollowingAfterUserLeavesBottom() {
        val state = SmartScrollState()

        state.update(value = 90, extent = 10, maximum = 100)

        assertNull(state.update(value = 70, extent = 10, maximum = 100))
        assertNull(state.update(value = 70, extent = 10, maximum = 140))
    }

    @Test
    fun resumesFollowingAfterUserReturnsToBottom() {
        val state = SmartScrollState()

        state.update(value = 90, extent = 10, maximum = 100)
        state.update(value = 70, extent = 10, maximum = 100)
        assertNull(state.update(value = 70, extent = 10, maximum = 140))
        assertNull(state.update(value = 130, extent = 10, maximum = 140))

        assertEquals(150, state.update(value = 130, extent = 10, maximum = 160))
    }
}
