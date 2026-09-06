package com.bagridmaster.app.media

import org.junit.Test

class GalleryCaptureBoundaryTest {
    private val boundary = GalleryCaptureBoundary(previousMaxId = 42, clearedAtMs = 10_250)

    @Test(expected = IllegalStateException::class)
    fun oldImageCannotBeReusedEvenIfMetadataWasUpdated() {
        boundary.validate(42, 20_000, 20_000)
    }

    @Test(expected = IllegalStateException::class)
    fun pendingOldScreenshotCannotBecomeNewByFinishingItsWrite() {
        boundary.validate(41, 20_000, 20_000)
    }

    @Test(expected = IllegalStateException::class)
    fun delayedInsertionOfOldScreenshotIsRejectedByCaptureTime() {
        boundary.validate(43, 20_000, 10_000)
    }

    @Test fun newScreenshotWithCaptureTimestampIsAccepted() {
        boundary.validate(43, 11_000, 10_800)
    }

    @Test fun newScreenshotWithoutCaptureTimestampUsesInsertionTime() {
        boundary.validate(43, 11_000, 0)
    }

    @Test(expected = IllegalStateException::class)
    fun ambiguousSameSecondWithoutCaptureTimestampIsRejected() {
        boundary.validate(43, 10_000, 0)
    }

    @Test fun initiallyEmptyGalleryAcceptsItsFirstNewScreenshot() {
        GalleryCaptureBoundary(0, 10_250).validate(1, 11_000, 11_100)
    }
}
