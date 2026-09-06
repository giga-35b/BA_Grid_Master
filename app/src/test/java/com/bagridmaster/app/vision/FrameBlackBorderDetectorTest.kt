package com.bagridmaster.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBlackBorderDetectorTest {
    @Test fun fourPixelsAreAllowedButFiveDisableCalibration() {
        val four = frame(left = 4)
        val five = frame(left = 5)

        assertEquals(4, FrameBlackBorderDetector.inspect(four).left)
        assertFalse(FrameBlackBorderDetector.inspect(four).exceedsCalibrationThreshold)
        assertEquals(5, FrameBlackBorderDetector.inspect(five).left)
        assertTrue(FrameBlackBorderDetector.inspect(five).exceedsCalibrationThreshold)
    }

    @Test fun detectsEveryPhysicalEdgeAndReportsThickness() {
        val borders = FrameBlackBorderDetector.inspect(frame(left = 6, top = 7, right = 8, bottom = 9))

        assertEquals(FrameBlackBorders(6, 7, 8, 9), borders)
        assertEquals("左6px、上7px、右8px、下9px", borders.label())
    }

    @Test fun darkPictureContentIsNotAFullWidthLetterboxLine() {
        val bytes = solidFrameBytes(100, 60)
        for (y in 0 until 55) for (x in 0 until 12) set(bytes, 100, x, y, 0)
        val borders = FrameBlackBorderDetector.inspect(RgbaFrame(100, 60, 400, bytes, 0))

        assertEquals(0, borders.left)
        assertFalse(borders.exceedsCalibrationThreshold)
    }

    @Test fun activeViewportUsesMedianRowsSoOverlayTextDoesNotShortenLetterbox() {
        val width = 120
        val height = 60
        val bytes = ByteArray(width * height * 4) { 0 }
        for (y in 0 until height) for (x in 20 until 100) set(bytes, width, x, y, 120)
        // A status panel intrudes into the left bar on a minority of rows.
        for (y in 10 until 22) for (x in 5 until 20) set(bytes, width, x, y, 90)
        val viewport = ActiveViewportDetector.detect(RgbaFrame(width, height, width * 4, bytes, 0))

        assertEquals(com.bagridmaster.app.analysis.ScreenRegion(20, 0, 100, 60), viewport)
    }

    private fun frame(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): RgbaFrame {
        val width = 100
        val height = 60
        val bytes = solidFrameBytes(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            if (x < left || y < top || x >= width - right || y >= height - bottom) set(bytes, width, x, y, 0)
        }
        return RgbaFrame(width, height, width * 4, bytes, 0)
    }

    private fun solidFrameBytes(width: Int, height: Int) = ByteArray(width * height * 4).also { bytes ->
        for (y in 0 until height) for (x in 0 until width) set(bytes, width, x, y, 120)
    }

    private fun set(bytes: ByteArray, width: Int, x: Int, y: Int, value: Int) {
        val offset = (y * width + x) * 4
        bytes[offset] = value.toByte()
        bytes[offset + 1] = value.toByte()
        bytes[offset + 2] = value.toByte()
        bytes[offset + 3] = 0xFF.toByte()
    }
}
