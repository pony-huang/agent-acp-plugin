package github.ponyhuang.acpplugin.toolwindow.ui

import javax.swing.DefaultBoundedRangeModel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class SmartScrollerTest {

    @Test
    fun keepsPinnedStateWhenContentGrowsAtBottom() {
        runOnEdt {
            val model = DefaultBoundedRangeModel(90, 10, 0, 100)
            val scrollBar = JScrollBar().apply { this.model = model }
            val smartScroller = SmartScroller(scrollBar)

            assertTrue(smartScroller.isPinnedToEnd())

            model.setRangeProperties(90, 10, 0, 160, false)
            flushEdt()

            assertTrue(smartScroller.isPinnedToEnd())

            smartScroller.scrollToTrackedPosition()

            assertEquals(150, scrollBar.value)
        }
    }

    @Test
    fun stopsFollowingAfterUserLeavesBottom() {
        runOnEdt {
            val model = DefaultBoundedRangeModel(90, 10, 0, 100)
            val scrollBar = JScrollBar().apply { this.model = model }
            val smartScroller = SmartScroller(scrollBar)

            scrollBar.value = 70
            flushEdt()

            assertFalse(smartScroller.isPinnedToEnd())

            model.setRangeProperties(70, 10, 0, 160, false)
            flushEdt()
            smartScroller.scrollToTrackedPosition()

            assertEquals(70, scrollBar.value)
        }
    }

    @Test
    fun runWithoutTrackingPreservesPinnedStateDuringProgrammaticScroll() {
        runOnEdt {
            val model = DefaultBoundedRangeModel(90, 10, 0, 100)
            val scrollBar = JScrollBar().apply { this.model = model }
            val smartScroller = SmartScroller(scrollBar)

            smartScroller.runWithoutTracking {
                scrollBar.value = 90
            }
            flushEdt()

            assertTrue(smartScroller.isPinnedToEnd())
        }
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

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    private fun flushEdt() {
        runOnEdt {}
    }
}
