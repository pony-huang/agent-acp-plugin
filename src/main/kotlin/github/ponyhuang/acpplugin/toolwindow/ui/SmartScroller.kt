package github.ponyhuang.acpplugin.toolwindow.ui

import java.awt.event.AdjustmentEvent
import java.awt.event.AdjustmentListener
import javax.swing.BoundedRangeModel
import javax.swing.JScrollBar
import javax.swing.SwingUtilities

internal enum class ViewportPosition {
    START,
    END
}

/**
 * Tracks whether the viewport is pinned to the configured edge while letting
 * callers own the actual programmatic scroll restoration.
 */
internal class SmartScroller(
    private val scrollBar: JScrollBar,
    private val viewportPosition: ViewportPosition = ViewportPosition.END,
    private val edgeTolerance: Int = 4
) : AdjustmentListener {
    private var suppressTrackingDepth = 0
    private var pinnedToViewportPosition = false
    private var previousValue = scrollBar.model.value
    private var previousExtent = scrollBar.model.extent
    private var previousMaximum = scrollBar.model.maximum

    init {
        pinnedToViewportPosition = isAtViewportPosition(scrollBar.model)
        scrollBar.addAdjustmentListener(this)
    }

    override fun adjustmentValueChanged(e: AdjustmentEvent) {
        SwingUtilities.invokeLater {
            val model = scrollBar.model
            if (suppressTrackingDepth > 0) {
                syncState(model)
                return@invokeLater
            }
            updatePinnedState(model)
            syncState(model)
        }
    }

    fun isPinnedToEnd(): Boolean = pinnedToViewportPosition

    fun scrollToTrackedPosition() {
        if (!pinnedToViewportPosition) {
            return
        }

        val model = scrollBar.model
        val targetValue = when (viewportPosition) {
            ViewportPosition.START -> 0
            ViewportPosition.END -> (model.maximum - model.extent).coerceAtLeast(0)
        }
        if (targetValue == model.value) {
            return
        }

        runWithoutTracking {
            scrollBar.value = targetValue
        }
    }

    fun runWithoutTracking(action: () -> Unit) {
        suppressTrackingDepth += 1
        try {
            action()
        } finally {
            suppressTrackingDepth -= 1
            val model = scrollBar.model
            pinnedToViewportPosition = isAtViewportPosition(model)
            syncState(model)
        }
    }

    fun dispose() {
        scrollBar.removeAdjustmentListener(this)
    }

    private fun isAtViewportPosition(model: BoundedRangeModel): Boolean {
        return when (viewportPosition) {
            ViewportPosition.START -> model.value <= edgeTolerance
            ViewportPosition.END -> model.maximum - (model.value + model.extent) <= edgeTolerance
        }
    }

    private fun updatePinnedState(model: BoundedRangeModel) {
        val valueChanged = model.value != previousValue
        val extentChanged = model.extent != previousExtent
        val maximumChanged = model.maximum != previousMaximum
        pinnedToViewportPosition = when {
            maximumChanged && !valueChanged && pinnedToViewportPosition -> true
            extentChanged && !valueChanged && pinnedToViewportPosition -> true
            else -> isAtViewportPosition(model)
        }
    }

    private fun syncState(model: BoundedRangeModel) {
        previousValue = model.value
        previousExtent = model.extent
        previousMaximum = model.maximum
    }
}

internal data class ScrollSnapshot(
    val value: Int,
    val extent: Int,
    val maximum: Int,
    val edgeTolerance: Int = 4
) {
    val wasAtEnd: Boolean
        get() = maximum - (value + extent) <= edgeTolerance

    fun restoreTarget(updatedModel: BoundedRangeModel): Int {
        val maxValue = (updatedModel.maximum - updatedModel.extent).coerceAtLeast(0)
        return if (wasAtEnd) {
            maxValue
        } else {
            value.coerceIn(0, maxValue)
        }
    }
}
