package com.bagridmaster.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryHideTimingTest {
    @Test fun newGalleryImageRestoresBeforeDeadline() {
        val timing = TemporaryHideTiming(10_000, 5)
        assertFalse(timing.shouldRestore(10_150, 42, 42))
        assertFalse(timing.shouldRestore(10_150, 42, null))
        assertTrue(timing.shouldRestore(10_150, 42, 43))
    }

    @Test fun timeoutRestoresAndDurationIsClamped() {
        assertEquals(1_000L, TemporaryHideTiming(0, 0).remainingMs(0))
        assertEquals(10_000L, TemporaryHideTiming(0, 99).remainingMs(0))
        val timing = TemporaryHideTiming(20_000, 5)
        assertFalse(timing.shouldRestore(24_999, 42, 42))
        assertTrue(timing.shouldRestore(25_000, 42, 42))
    }
}
