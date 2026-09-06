package com.bagridmaster.app.overlay

internal data class DragOffset(val x: Int, val y: Int)

internal enum class OverlayGestureOutcome { NONE, TAP, LONG_PRESS, DRAG }

internal data class OverlayGestureEnd(
    val outcome: OverlayGestureOutcome,
    val offset: DragOffset? = null,
)

/** Tracks the whole gesture, so dragging out and back cannot turn into a click. */
internal class OverlayGestureTracker(
    private val touchSlop: Int,
    private val longPressTimeoutMs: Long,
) {
    private var active = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun begin(x: Float, y: Float, time: Long) {
        active = true
        dragging = false
        downX = x
        downY = y
        downTime = time
    }

    fun move(x: Float, y: Float): DragOffset? {
        if (!active) return null
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy > touchSlop.toFloat() * touchSlop) dragging = true
        return if (dragging) DragOffset(dx.toInt(), dy.toInt()) else null
    }

    fun end(x: Float, y: Float, time: Long): OverlayGestureEnd {
        if (!active) return OverlayGestureEnd(OverlayGestureOutcome.NONE)
        val offset = move(x, y)
        val outcome = when {
            dragging -> OverlayGestureOutcome.DRAG
            time - downTime >= longPressTimeoutMs -> OverlayGestureOutcome.LONG_PRESS
            else -> OverlayGestureOutcome.TAP
        }
        // End the gesture before the caller invokes any view/application callbacks.
        cancel()
        return OverlayGestureEnd(outcome, offset)
    }

    fun cancel() {
        active = false
        dragging = false
    }
}
