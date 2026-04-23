package com.github.ponyhuang.agentacpplugin.toolwindow.ui

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
 * Smart scroll behavior based on Rob Camick's "Smart Scrolling" approach:
 * keep following appended content only while the user is already at the end.
 */
internal class SmartScroller(
    private val scrollBar: JScrollBar,
    private val viewportPosition: ViewportPosition = ViewportPosition.END,
    private val state: SmartScrollState = SmartScrollState(viewportPosition)
) : AdjustmentListener {

    init {
        scrollBar.addAdjustmentListener(this)
    }

    override fun adjustmentValueChanged(e: AdjustmentEvent) {
        SwingUtilities.invokeLater {
            val model = scrollBar.model
            val targetValue = state.update(model) ?: return@invokeLater
            if (targetValue == model.value) {
                return@invokeLater
            }

            scrollBar.removeAdjustmentListener(this)
            scrollBar.value = targetValue
            scrollBar.addAdjustmentListener(this)
        }
    }

    fun dispose() {
        scrollBar.removeAdjustmentListener(this)
    }
}

internal class SmartScrollState(
    private val viewportPosition: ViewportPosition = ViewportPosition.END
) {
    private var adjustScrollBar = true
    private var previousValue = -1
    private var previousMaximum = -1

    fun update(model: BoundedRangeModel): Int? = update(
        value = model.value,
        extent = model.extent,
        maximum = model.maximum
    )

    fun update(value: Int, extent: Int, maximum: Int): Int? {
        val valueChanged = previousValue != value
        val maximumChanged = previousMaximum != maximum

        if (valueChanged && !maximumChanged) {
            adjustScrollBar = when (viewportPosition) {
                ViewportPosition.END -> value + extent >= maximum
                ViewportPosition.START -> value == 0
            }
        }

        val targetValue = when {
            !adjustScrollBar -> null
            viewportPosition == ViewportPosition.END -> maximum - extent
            maximumChanged && previousMaximum >= 0 -> value + maximum - previousMaximum
            else -> null
        }?.coerceAtLeast(0)

        previousValue = value
        previousMaximum = maximum

        return targetValue?.takeUnless { it == value }
    }
}
