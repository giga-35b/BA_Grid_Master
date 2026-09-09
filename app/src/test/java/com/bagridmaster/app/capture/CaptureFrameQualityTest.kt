package com.bagridmaster.app.capture

import com.bagridmaster.app.vision.LocalTemplateMatcherTest
import com.bagridmaster.app.vision.RgbaFrame
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CaptureFrameQualityTest {
    @Test fun rejectsReportedPortraitSurfaceLandscapeFrameWithOffset957AndScale045() {
        // The exact failure: output 2400x1080, content width1080 and y=957..1080.
        val frame = image(2400, 1080) { x, y -> x < 1080 && y >= 957 }
        assertTrue(CaptureFrameQuality.problem(frame)!!.contains("不完整"))
    }

    @Test fun rejectsBlackFramesWithoutMistakingThemForUnrecognizedGameBoards() {
        assertTrue(CaptureFrameQuality.problem(image(240, 108) { _, _ -> false })!!.contains("黑屏"))
    }

    @Test fun acceptsNormalFramesAndOrdinaryCenteredLetterboxing() {
        assertNull(CaptureFrameQuality.problem(image(240, 108) { _, _ -> true }))
        assertNull(CaptureFrameQuality.problem(image(240, 108) { x, _ -> x in 24..215 }))
        assertNull(CaptureFrameQuality.problem(image(108, 240) { _, y -> y in 95..143 }))
        // A small centered loading message is not the corner-strip transport defect.
        assertNull(CaptureFrameQuality.problem(image(240, 108) { x, y -> x in 100..140 && y in 45..65 }))
    }

    @Test fun acceptsAllDatasetFramesIncludingNonGameScreensForNormalCvRejection() {
        val root = sequenceOf(File("dataset"), File("../dataset")).first { it.isDirectory }
        val images = root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.toList()
        assertEquals(59, images.size)
        images.forEach { file ->
            assertNull(file.name, CaptureFrameQuality.problem(LocalTemplateMatcherTest.readFrame(file.absolutePath)))
        }
    }

    private fun image(width: Int, height: Int, lit: (Int, Int) -> Boolean): RgbaFrame {
        val stride = width * 4 + 16 // Also exercise padded rows.
        val bytes = ByteArray(stride * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = y * stride + x * 4
            if (lit(x, y)) { bytes[offset] = 40; bytes[offset + 1] = 110; bytes[offset + 2] = 200.toByte() }
            bytes[offset + 3] = 255.toByte()
        }
        return RgbaFrame(width, height, stride, bytes, 1L)
    }
}
