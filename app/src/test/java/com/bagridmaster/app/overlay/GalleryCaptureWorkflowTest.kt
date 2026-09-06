package com.bagridmaster.app.overlay

import com.bagridmaster.app.media.GalleryCaptureBoundary
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryCaptureWorkflowTest {
    private val workflow = GalleryCaptureWorkflow()

    @Test(expected = IllegalStateException::class)
    fun firstRecognitionAlsoRequiresCleanScreenshotPreparation() {
        workflow.requireReady()
    }

    @Test(expected = IllegalStateException::class)
    fun cannotRecognizeBeforePreparationDelay() {
        workflow.beginClearing()
        workflow.requireReady()
    }

    @Test fun threeRecognitionCyclesEachRequireANewClearAndImage() {
        repeat(3) { index ->
            assertEquals(GalleryCaptureWorkflow.Phase.NEEDS_CLEAR, workflow.phase)
            workflow.beginClearing()
            val boundary = GalleryCaptureBoundary(index.toLong(), index * 10_000L)
            workflow.ready()
            workflow.requireReady()
            boundary.validate(index + 1L, index * 10_000L + 2_000, 0)
            workflow.reset() // Successful recognition consumes this preparation.
        }
    }

    @Test fun retryAfterMissingScreenshotRetainsCleanBoundary() {
        val boundary = GalleryCaptureBoundary(42, 10_250)
        workflow.beginClearing()
        workflow.ready()
        runCatching { boundary.validate(42, 10_000, 10_000) }
            .onSuccess { error("Expected stale image rejection") }
        assertEquals(GalleryCaptureWorkflow.Phase.READY, workflow.phase)
        workflow.requireReady()
        boundary.validate(43, 12_000, 12_200)
    }

    @Test fun longPressRearmsAndRequiresPreparationAgain() {
        workflow.beginClearing()
        workflow.ready()
        workflow.beginClearing()
        assertEquals(GalleryCaptureWorkflow.Phase.CLEARING, workflow.phase)
        workflow.ready()
        workflow.requireReady()
    }

    @Test fun modeSwitchOrPreparationFailureResetsWorkflow() {
        workflow.beginClearing()
        workflow.reset()
        assertEquals(GalleryCaptureWorkflow.Phase.NEEDS_CLEAR, workflow.phase)
    }
}
