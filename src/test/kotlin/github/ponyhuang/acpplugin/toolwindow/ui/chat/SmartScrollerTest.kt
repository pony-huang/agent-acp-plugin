package github.ponyhuang.acpplugin.toolwindow.ui.chat

import javax.swing.DefaultBoundedRangeModel
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

    @Test
    fun scrollSnapshotKeepsBottomPinnedAfterRebuild() {
        val snapshot = ScrollSnapshot(value = 90, extent = 10, maximum = 100)
        val updatedModel = DefaultBoundedRangeModel(0, 10, 0, 160)

        assertEquals(150, snapshot.restoreTarget(updatedModel))
    }

    @Test
    fun scrollSnapshotPreservesOffsetWhenUserWasNotAtBottom() {
        val snapshot = ScrollSnapshot(value = 70, extent = 10, maximum = 100)
        val updatedModel = DefaultBoundedRangeModel(0, 10, 0, 160)

        assertEquals(70, snapshot.restoreTarget(updatedModel))
    }
}
