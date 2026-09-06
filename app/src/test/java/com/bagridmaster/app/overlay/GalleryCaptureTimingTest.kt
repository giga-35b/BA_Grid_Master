package com.bagridmaster.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryCaptureTimingTest {
    private val timing = GalleryCaptureTiming()

    @Test fun recognitionUnlocksAtHalfSecondWhilePanelIsStillTenPercent() {
        timing.start(10_000)
        assertEquals(500L, timing.readyDelayMs(10_000))
        assertEquals(1L, timing.readyDelayMs(10_499))
        assertEquals(0L, timing.readyDelayMs(10_500))
        assertEquals(2_500L, timing.dimDelayMs(10_500))
        assertEquals(0.10f, timing.alpha(10_500, 0.92f), 0f)
    }

    @Test fun analysisCompletionDoesNotEndTheIndependentDimmingPeriod() {
        val workflow = GalleryCaptureWorkflow()
        timing.start(10_000)
        workflow.beginClearing()
        workflow.ready()
        workflow.requireReady() // Recognition at 0.5s while the panel is still visible.
        workflow.reset() // Result arrives before 3s.
        assertEquals(0.10f, timing.alpha(11_000, 0.92f), 0f)
        assertEquals(0.10f, timing.alpha(12_999, 0.92f), 0f)
        assertEquals(0.92f, timing.alpha(13_000, 0.92f), 0f)
    }

    @Test fun settingsRefreshCannotOverrideDimmingAndRestoresLatestConfiguredAlpha() {
        timing.start(0)
        assertEquals(0.10f, timing.alpha(1_000, 0.50f), 0f)
        assertEquals(0.50f, timing.alpha(3_000, 0.50f), 0f)
    }

    @Test fun anotherClearReplacesBothDeadlines() {
        timing.start(0)
        timing.start(2_000)
        assertEquals(500L, timing.readyDelayMs(2_000))
        assertEquals(0.10f, timing.alpha(3_000, 0.92f), 0f)
        assertEquals(0.92f, timing.alpha(5_000, 0.92f), 0f)
    }

    @Test fun modeSwitchRestoresConfiguredOpacityImmediately() {
        timing.start(0)
        timing.reset()
        assertEquals(0L, timing.dimDelayMs(500))
        assertEquals(0.75f, timing.alpha(500, 0.75f), 0f)
    }

    @Test fun setupTimeDoesNotAddAnotherHalfSecondDelay() {
        timing.start(10_000)
        assertEquals(400L, timing.readyDelayMs(10_100))
        assertEquals(0L, timing.readyDelayMs(10_600))
        assertEquals(0L, timing.dimDelayMs(14_000))
    }
}
