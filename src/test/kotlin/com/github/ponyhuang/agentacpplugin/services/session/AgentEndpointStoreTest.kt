package com.github.ponyhuang.agentacpplugin.services.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEndpointStoreTest {
    @Test
    fun testAllSessionsSortByMostRecentActivity() {
        val store = AgentEndpointStore()
        store.createEndpoint("endpoint-1", "agent")
        store.createSession("endpoint-1", "session-1", "First")
        Thread.sleep(5)
        store.createSession("endpoint-1", "session-2", "Second")

        assertEquals(listOf("session-2", "session-1"), store.allSessions().map { it.sessionId })

        Thread.sleep(5)
        val previousActivity = store.getSession("session-1")!!.lastActivityAt
        store.updateSession("session-1") { it.copy(title = "First updated") }

        val updated = store.getSession("session-1")!!
        assertTrue(updated.lastActivityAt.isAfter(previousActivity))
        assertEquals(listOf("session-1", "session-2"), store.allSessions().map { it.sessionId })
    }
}
