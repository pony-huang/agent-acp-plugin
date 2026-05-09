package github.ponyhuang.acpplugin.toolwindow.ui

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class MessageRowReconciliationPolicyTest {
    private val policy = MessageRowReconciliationPolicy()

    @Test
    fun keepsExistingRowsWhenOrderAndStructureAreStable() {
        val plan = policy.decide(
            previousMessageIds = listOf("one", "two"),
            nextMessageIds = listOf("one", "two"),
            showEmptyState = false,
            hadEmptyState = false,
            replacedExistingRow = false
        )

        assertTrue(plan.staleMessageIds.isEmpty())
        assertEquals(MessageRowChildMutation.Keep, plan.mutation)
    }

    @Test
    fun appendsRowsWhenNextIdsExtendPreviousIds() {
        val plan = policy.decide(
            previousMessageIds = listOf("one"),
            nextMessageIds = listOf("one", "two", "three"),
            showEmptyState = false,
            hadEmptyState = false,
            replacedExistingRow = false
        )

        assertEquals(
            MessageRowChildMutation.Append(
                appendedMessageIds = listOf("two", "three"),
                startRow = 1
            ),
            plan.mutation
        )
    }

    @Test
    fun rebuildsWhenRowsAreReordered() {
        val plan = policy.decide(
            previousMessageIds = listOf("one", "two"),
            nextMessageIds = listOf("two", "one"),
            showEmptyState = false,
            hadEmptyState = false,
            replacedExistingRow = false
        )

        assertEquals(
            MessageRowChildMutation.Rebuild(
                orderedMessageIds = listOf("two", "one"),
                showEmptyState = false
            ),
            plan.mutation
        )
    }

    @Test
    fun rebuildsEmptyStateAndReportsStaleRows() {
        val plan = policy.decide(
            previousMessageIds = listOf("one"),
            nextMessageIds = emptyList(),
            showEmptyState = true,
            hadEmptyState = false,
            replacedExistingRow = false
        )

        assertEquals(listOf("one"), plan.staleMessageIds)
        assertEquals(
            MessageRowChildMutation.Rebuild(
                orderedMessageIds = emptyList(),
                showEmptyState = true
            ),
            plan.mutation
        )
    }
}
