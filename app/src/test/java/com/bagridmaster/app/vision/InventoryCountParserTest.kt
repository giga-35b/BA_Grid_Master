package com.bagridmaster.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bagridmaster.app.analysis.*

class InventoryCountParserTest {
    @Test fun boardDigitsAreNegativeOnlyWhenSpriteArtworkHasNone() {
        val board = ScreenRegion(600, 100, 1100, 400)
        val sprites = listOf(ScreenRegion(100, 450, 250, 550), ScreenRegion(260, 450, 410, 550))
        val headerDigit = LocatedDigitToken("38/45", 800, 120)
        val artworkDigit = LocatedDigitToken("7", 180, 490)
        val first = classifySpatialDigits(listOf(headerDigit), board, sprites)
        assertEquals(listOf(headerDigit), first.boardTokens)
        assertTrue(first.spriteTokens.isEmpty())
        val guarded = classifySpatialDigits(listOf(headerDigit, artworkDigit), board, sprites)
        assertEquals(listOf(artworkDigit), guarded.spriteTokens)
    }
    @Test
    fun parsesMultiplierAndFinishStates() {
        assertEquals(4, parseInventoryCount("×4").remainingCount)
        assertEquals(12, parseInventoryCount("x12").remainingCount)
        assertFalse(parseInventoryCount("×4").isFinished)
        assertTrue(parseInventoryCount("Finish").isFinished)
        assertEquals(0, parseInventoryCount("×O").remainingCount)
    }

    private fun card() = ItemCardRecognition(0, ScreenRegion(0, 0, 100, 100), ScreenRegion(0, 0, 100, 70),
        ScreenRegion(0, 70, 30, 100), ScreenRegion(70, 70, 100, 100), null, null, false, 0.35,
        RgbaPatch(1, 1, byteArrayOf(0, 0, 0, -1)))

    @Test fun centerFinishRecoversMissingZeroWithoutRequiringShape() {
        val result = resolveInventoryTexts(card(), "", "Finish")
        assertTrue(result.isFinished)
        assertEquals(0, result.remainingCount)
        assertEquals(null, result.shape)
        assertTrue(result.finishEvidence.contains("中央"))
    }

    @Test fun strongVisualFinishSurvivesBothOcrMisses() {
        val result = resolveInventoryTexts(card().copy(isFinished = true, remainingCount = 0,
            finishEvidence = "中央 Finish 黄字字形与深色横幅均通过"), "", "")
        assertTrue(result.isFinished)
        assertEquals(0, result.remainingCount)
    }

    @Test fun contradictoryPositiveCountDoesNotBecomeCompleted() {
        val result = resolveInventoryTexts(card().copy(isFinished = true), "×3", "Finish")
        assertFalse(result.isFinished)
        assertEquals(null, result.remainingCount)
        assertTrue(result.finishEvidence.contains("冲突"))
    }

    @Test fun artworkDigitsAndSimilarWordsDoNotInventCountsOrFinish() {
        for (text in listOf("123", "finished", "unfinish", "Fin ish")) {
            val result = resolveInventoryTexts(card(), "", text)
            assertFalse(text, result.isFinished)
            assertEquals(null, result.remainingCount)
        }
        assertEquals(4, resolveInventoryTexts(card(), "×4", "").remainingCount)
    }

    @Test fun expandsRetryRegionWithinCardBounds() {
        assertEquals(
            ScreenRegion(67, 67, 100, 100),
            expandedCountRegion(ScreenRegion(70, 70, 100, 100), ScreenRegion(0, 0, 100, 100)),
        )
    }

    @Test fun recoversOnlyMissingOneWhenRemainingAndCompletedCellsReachThirty() {
        val cards = listOf(
            card().copy(index = 0, shape = rectangle(1, 3), remainingCount = 4),
            card().copy(index = 1, shape = rectangle(1, 4), remainingCount = 2),
            card().copy(index = 2, shape = rectangle(2, 3), remainingCount = null),
        )
        val result = recoverSingleMissingCountFromThirtyCells(cards, completedCellCount = 4)
        assertEquals(listOf(4, 2, 1), result.inventory.map { it.remainingCount })
        assertTrue(result.note.orEmpty().contains("= 30格"))
        assertTrue(result.inventory[2].countEvidence.contains("数量按1"))
    }

    @Test fun doesNotForceThirtyWhenKnownCountsAreCompleteOrAssumptionDoesNotFit() {
        val complete = listOf(
            card().copy(index = 0, shape = rectangle(1, 2), remainingCount = 1),
            card().copy(index = 1, shape = rectangle(1, 3), remainingCount = 1),
            card().copy(index = 2, shape = rectangle(1, 4), remainingCount = 1),
        )
        assertEquals(listOf(1, 1, 1), recoverSingleMissingCountFromThirtyCells(complete, 0).inventory.map { it.remainingCount })

        val nonMatching = complete.mapIndexed { index, item ->
            if (index == 2) item.copy(remainingCount = null) else item
        }
        assertEquals(null, recoverSingleMissingCountFromThirtyCells(nonMatching, 0).inventory[2].remainingCount)
    }

    @Test fun doesNotGuessWhenMoreThanOneCountOrAnyActiveShapeIsMissing() {
        val twoUnknown = listOf(
            card().copy(index = 0, shape = rectangle(1, 3), remainingCount = 4),
            card().copy(index = 1, shape = rectangle(1, 4), remainingCount = null),
            card().copy(index = 2, shape = rectangle(2, 3), remainingCount = null),
        )
        assertTrue(recoverSingleMissingCountFromThirtyCells(twoUnknown, 12).inventory.drop(1).all { it.remainingCount == null })

        val missingShape = twoUnknown.mapIndexed { index, item ->
            when (index) {
                1 -> item.copy(shape = null, remainingCount = 2)
                else -> item
            }
        }
        assertEquals(null, recoverSingleMissingCountFromThirtyCells(missingShape, 4).inventory[2].remainingCount)
    }

    private fun rectangle(rows: Int, columns: Int) = ItemShape(
        rows,
        columns,
        (0 until rows).flatMap { row -> (0 until columns).map { column -> ShapeCell(row, column) } }.toSet(),
    )
}
