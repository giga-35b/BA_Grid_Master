package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.RgbaPatch
import org.junit.Assert.*
import org.junit.Test

class FinishLabelDetectorTest {
    @Test fun darkenedArtworkIsNotACompletedLabel() {
        val (_, detection) = LocalTemplateMatcherTest.load("dataset/202608292110_1st_round/Screenshot_2026-08-29-21-06-10-167_com.YostarJP..jpg")
        for (card in detection.itemCards) {
            assertFalse(FinishLabelDetector.detect(card.spriteTemplate))
            val dimmed = card.spriteTemplate.rgba8888.copyOf()
            for (i in dimmed.indices) if (i % 4 != 3) dimmed[i] = ((dimmed[i].toInt() and 255) * 0.4).toInt().toByte()
            assertFalse(FinishLabelDetector.detect(card.spriteTemplate.copy(rgba8888 = dimmed)))
        }
    }

    @Test fun sixYellowBlobsWithNarrowSecondAndFourthAreNotFinish() {
        val width = 196; val height = 136
        val bytes = ByteArray(width * height * 4)
        for (i in bytes.indices step 4) {
            bytes[i] = 28; bytes[i + 1] = 40; bytes[i + 2] = 64; bytes[i + 3] = -1
        }
        var left = 57
        for (glyphWidth in listOf(12, 4, 12, 4, 12, 12)) {
            for (y in 50 until 70) for (x in left until left + glyphWidth) {
                val i = (y * width + x) * 4
                bytes[i] = -1; bytes[i + 1] = -15; bytes[i + 2] = 15
            }
            left += glyphWidth + 2
        }
        assertFalse("F structure is required, not just six yellow blobs", FinishLabelDetector.detect(RgbaPatch(width, height, bytes)))
    }

    @Test fun finishedCardMayLackReadableShapeOrCount() {
        val (_, detection) = LocalTemplateMatcherTest.load("dataset/202608292110_1st_round/Screenshot_2026-08-29-21-10-18-410_com.YostarJP..jpg")
        val card = detection.itemCards[2]
        assertTrue(card.isFinished)
        val resolved = resolveInventoryTexts(card.copy(shape = null), "", "")
        assertTrue(resolved.isFinished)
        assertEquals(0, resolved.remainingCount)
    }
}
