package com.bagridmaster.app.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Only the action button is clickable; the panel background is a drag surface. */
internal class OverlayTouchController(
    private val root: View,
    private val action: View,
    private val readPosition: () -> Pair<Int, Int>,
    private val moveTo: (Int, Int) -> Unit,
    private val onDragFinished: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Boolean,
) {
    private var disposed = false
    private var pendingFinish: Runnable? = null
    private val panelTouch = TouchListener(clickTarget = null)
    private val buttonTouch = TouchListener(clickTarget = action)

    init {
        root.setOnClickListener(null)
        root.setOnLongClickListener(null)
        root.isClickable = false
        root.isLongClickable = false
        root.setOnTouchListener(panelTouch)
        action.setOnClickListener { if (!disposed) onClick() }
        action.setOnLongClickListener { !disposed && onLongClick() }
        action.setOnTouchListener(buttonTouch)
    }

    fun dispose() {
        disposed = true
        cancelPendingFinish()
        panelTouch.gesture.cancel()
        buttonTouch.gesture.cancel()
        root.setOnTouchListener(null)
        action.setOnTouchListener(null)
        action.setOnClickListener(null)
        action.setOnLongClickListener(null)
    }

    private fun cancelPendingFinish() {
        pendingFinish?.let { root.removeCallbacks(it) }
        pendingFinish = null
    }

    private fun postDragFinished() {
        cancelPendingFinish()
        val task = object : Runnable {
            override fun run() {
                if (disposed || pendingFinish !== this) return
                pendingFinish = null
                onDragFinished()
            }
        }
        pendingFinish = task
        // Reparenting a child inside its ACTION_UP dispatch sends it ACTION_CANCEL
        // before removal completes. Wait for dispatch to finish before rebuilding.
        root.post(task)
    }

    private inner class TouchListener(private val clickTarget: View?) : View.OnTouchListener {
        val gesture = OverlayGestureTracker(
            ViewConfiguration.get(root.context).scaledTouchSlop,
            ViewConfiguration.getLongPressTimeout().toLong(),
        )
        private var startX = 0
        private var startY = 0

        // The background deliberately has no click action. Button taps use performClick
        // so accessibility-triggered clicks and touch clicks share the same action.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(touched: View?, event: MotionEvent): Boolean {
            if (disposed) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingFinish()
                    panelTouch.gesture.cancel()
                    buttonTouch.gesture.cancel()
                    val position = readPosition()
                    startX = position.first
                    startY = position.second
                    gesture.begin(event.rawX, event.rawY, event.eventTime)
                }
                MotionEvent.ACTION_MOVE -> {
                    gesture.move(event.rawX, event.rawY)?.let { offset ->
                        moveTo(startX + offset.x, startY + offset.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val end = gesture.end(event.rawX, event.rawY, event.eventTime)
                    when (end.outcome) {
                        OverlayGestureOutcome.TAP -> clickTarget?.performClick()
                        OverlayGestureOutcome.LONG_PRESS -> clickTarget?.performLongClick()
                        OverlayGestureOutcome.DRAG -> {
                            end.offset?.let { moveTo(startX + it.x, startY + it.y) }
                            postDragFinished()
                        }
                        OverlayGestureOutcome.NONE -> Unit
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Cancellation can originate from layout rebuild or service teardown.
                    // Never snap, rebuild, persist or click from this branch.
                    gesture.cancel()
                    cancelPendingFinish()
                }
            }
            return true
        }
    }
}
