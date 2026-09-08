package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBoundaryContactsTest {
    @Test fun threeSidedTextureIsInvariantToThemeHueAndDarkArtwork() {
        val themes = listOf(
            intArrayOf(205, 225, 120) to arrayOf(intArrayOf(15, 30, 55), intArrayOf(235, 80, 150)),
            intArrayOf(90, 185, 175) to arrayOf(intArrayOf(245, 235, 210), intArrayOf(20, 25, 30)),
            intArrayOf(190, 105, 210) to arrayOf(intArrayOf(20, 180, 220), intArrayOf(245, 180, 35)),
        )
        for ((background, artwork) in themes) {
            val frame = frame(background, artwork)
            val edges = AdaptiveBoundaryContacts.measure(frame, ScreenRegion(0, 0, 64, 64))
            assertTrue("theme=${background.toList()} top=${edges[0]}", edges[0] >= 0.12)
            assertTrue("theme=${background.toList()} right=${edges[1]}", edges[1] < 0.12)
            assertTrue("theme=${background.toList()} bottom=${edges[2]}", edges[2] >= 0.12)
            assertTrue("theme=${background.toList()} left=${edges[3]}", edges[3] >= 0.12)
        }
    }

    @Test fun mildBackgroundVariationDoesNotBecomeContinuation() {
        val bytes = ByteArray(64 * 64 * 4)
        for (y in 0 until 64) for (x in 0 until 64) {
            val drift = (x + y) / 12
            set(bytes, x, y, intArrayOf(125 + drift, 170 + drift, 210 + drift))
        }
        val edges = AdaptiveBoundaryContacts.measure(RgbaFrame(64, 64, 256, bytes, 0), ScreenRegion(0, 0, 64, 64))
        assertTrue(edges.contentToString(), edges.all { it < 0.12 })
    }

    @Test fun learnedBackgroundVetoesTexturedTopAndRightBands() {
        val backgroundA = intArrayOf(105, 174, 214)
        val backgroundB = intArrayOf(128, 190, 225)
        val artwork = arrayOf(intArrayOf(245, 115, 72), intArrayOf(250, 225, 205))
        val width = 192
        val bytes = ByteArray(width * 64 * 4)
        for (y in 0 until 64) for (x in 0 until width) {
            val localX = x % 64
            val background = if ((localX / 4 + y / 5) % 2 == 0) backgroundA else backgroundB
            val objectPixel = x >= 128 &&
                ((localX in 2..48 && y in 9..61) || (localX in 2..55 && y in 18..61))
            set(bytes, width, x, y, if (objectPixel) artwork[(localX / 3 + y / 4) % 2] else background)
        }
        val frame = RgbaFrame(width, 64, width * 4, bytes, 0)
        val background = checkNotNull(OpenCellBackgroundModel.learn(frame,
            listOf(ScreenRegion(0, 0, 64, 64), ScreenRegion(64, 0, 128, 64))))
        val edges = AdaptiveBoundaryContacts.measure(
            frame, ScreenRegion(128, 0, 192, 64), background)
        assertTrue(edges.contentToString(), edges[0] < 0.12)
        assertTrue(edges.contentToString(), edges[1] < 0.12)
        assertTrue(edges.contentToString(), edges[2] >= 0.12)
        assertTrue(edges.contentToString(), edges[3] >= 0.12)
    }

    private fun frame(background: IntArray, artwork: Array<IntArray>): RgbaFrame {
        val bytes = ByteArray(64 * 64 * 4)
        for (y in 0 until 64) for (x in 0 until 64) {
            val textured = (y in 2..12 && x in 7..45) || (y in 51..61 && x in 7..45) ||
                (x in 2..12 && y in 7..56)
            val colour = if (textured) artwork[(x / 4 + y / 5) % artwork.size] else background
            set(bytes, x, y, colour)
        }
        return RgbaFrame(64, 64, 256, bytes, 0)
    }

    private fun set(bytes: ByteArray, x: Int, y: Int, colour: IntArray) =
        set(bytes, 64, x, y, colour)

    private fun set(bytes: ByteArray, width: Int, x: Int, y: Int, colour: IntArray) {
        val offset = (y * width + x) * 4
        bytes[offset] = colour[0].toByte(); bytes[offset + 1] = colour[1].toByte()
        bytes[offset + 2] = colour[2].toByte(); bytes[offset + 3] = 255.toByte()
    }
}
