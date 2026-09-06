package com.bagridmaster.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayGestureTrackerTest {
    private val gesture = OverlayGestureTracker(touchSlop = 8, longPressTimeoutMs = 500)

    @Test fun tapWithSmallJitterDoesNotMoveWindow() {
        gesture.begin(100f, 100f, 0)
        assertNull(gesture.move(103f, 104f))
        assertEquals(OverlayGestureOutcome.TAP, gesture.end(103f, 104f, 100).outcome)
    }

    @Test fun longPressDoesNotBecomeTap() {
        gesture.begin(100f, 100f, 0)
        assertEquals(OverlayGestureOutcome.LONG_PRESS, gesture.end(100f, 100f, 500).outcome)
    }

    @Test fun dragReturnsOffsetAndEndsOnlyOnce() {
        gesture.begin(100f, 100f, 0)
        assertEquals(DragOffset(20, -10), gesture.move(120f, 90f))
        assertEquals(OverlayGestureEnd(OverlayGestureOutcome.DRAG, DragOffset(40, -20)), gesture.end(140f, 80f, 100))
        assertEquals(OverlayGestureOutcome.NONE, gesture.end(140f, 80f, 101).outcome)
        assertNull(gesture.move(200f, 200f))
    }

    @Test fun draggingBackToStartNeverTriggersTapOrLongPress() {
        gesture.begin(100f, 100f, 0)
        gesture.move(150f, 100f)
        gesture.move(100f, 100f)
        assertEquals(OverlayGestureEnd(OverlayGestureOutcome.DRAG, DragOffset(0, 0)), gesture.end(100f, 100f, 700))
    }

    @Test fun releaseCanCrossSlopWithoutIntermediateMoveEvent() {
        gesture.begin(100f, 100f, 0)
        assertEquals(OverlayGestureOutcome.DRAG, gesture.end(110f, 100f, 100).outcome)
    }

    @Test fun cancellationIgnoresLaterReleaseAndMove() {
        gesture.begin(100f, 100f, 0)
        gesture.move(150f, 100f)
        gesture.cancel()
        assertNull(gesture.move(160f, 100f))
        assertEquals(OverlayGestureOutcome.NONE, gesture.end(160f, 100f, 100).outcome)
    }

    @Test fun cancellationAfterCompletedDragDoesNotReenterGesture() {
        gesture.begin(100f, 100f, 0)
        assertEquals(OverlayGestureOutcome.DRAG, gesture.end(150f, 100f, 100).outcome)
        gesture.cancel()
        gesture.cancel()
        assertEquals(OverlayGestureOutcome.NONE, gesture.end(150f, 100f, 101).outcome)
    }

    @Test fun newGestureAfterCancelledDragCanTapNormally() {
        gesture.begin(100f, 100f, 0)
        gesture.move(150f, 100f)
        gesture.cancel()
        gesture.begin(200f, 200f, 1000)
        assertEquals(OverlayGestureOutcome.TAP, gesture.end(200f, 200f, 1100).outcome)
    }

    @Test fun newGestureResetsCompletedDragHistory() {
        gesture.begin(100f, 100f, 0)
        gesture.end(150f, 100f, 100)
        gesture.begin(200f, 200f, 1000)
        assertEquals(OverlayGestureOutcome.TAP, gesture.end(200f, 200f, 1100).outcome)
    }
}
