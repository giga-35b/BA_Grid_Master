package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.ItemShape
import com.bagridmaster.app.analysis.RgbaPatch
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.ShapeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTemplateStoreTest {
    private val region = ScreenRegion(0, 0, 100, 80)
    private fun shape(rows: Int, columns: Int) = ItemShape(rows, columns, buildSet {
        for (row in 0 until rows) for (column in 0 until columns) add(ShapeCell(row, column))
    })
    private fun card(index: Int, shape: ItemShape?, finished: Boolean, marker: Byte) = ItemCardRecognition(
        index, region, region, region, region, shape, if (finished) 0 else 1, finished, 0.9,
        RgbaPatch(1, 1, byteArrayOf(marker, marker, marker, 255.toByte())),
    )

    @Test fun twoMatchingPeersAllowMissingFinishShapeAndArtworkToCarryWithinRound() {
        val previous = listOf(card(0, shape(2, 3), false, 10), card(1, shape(4, 2), false, 20),
            card(2, shape(3, 3), false, 30))
        ItemTemplateStore.update(previous)
        try {
            val current = listOf(card(0, shape(2, 3), false, 40), card(1, null, true, 50),
                card(2, shape(3, 3), false, 60))

            val recovered = ItemTemplateStore.recoverFinishedArtwork(current)

            assertEquals(shape(4, 2), recovered[1].shape)
            assertSame(previous[1].spriteTemplate, recovered[1].spriteTemplate)
            assertTrue(recovered[1].finishEvidence.contains("同轮"))
        } finally {
            ItemTemplateStore.update(emptyList())
        }
    }

    @Test fun oneMatchingPeerIsNotEnoughToCarryAnUnknownFinishShape() {
        val previous = listOf(card(0, shape(2, 3), false, 10), card(1, shape(4, 2), false, 20),
            card(2, shape(2, 4), false, 30))
        ItemTemplateStore.update(previous)
        try {
            val current = listOf(card(0, shape(2, 3), false, 40), card(1, null, true, 50),
                card(2, shape(3, 3), false, 60))
            assertEquals(null, ItemTemplateStore.recoverFinishedArtwork(current)[1].shape)
        } finally {
            ItemTemplateStore.update(emptyList())
        }
    }
}
