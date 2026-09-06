package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.ItemShape
import com.bagridmaster.app.analysis.RgbaPatch
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.ShapeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeBoardFinishedShapeTest {
    private val board = BoardGeometry(ScreenRegion(0, 0, 216, 120), 5, 9, 1.0)

    @Test fun finishShapeCanRecoverAWholeSilhouetteWithoutOpenObjectSeed() {
        val bytes = ByteArray(216 * 120 * 4)
        for (offset in bytes.indices step 4) {
            bytes[offset] = 220.toByte(); bytes[offset + 1] = 220.toByte()
            bytes[offset + 2] = 220.toByte(); bytes[offset + 3] = 255.toByte()
        }
        val footprint = buildSet {
            for (row in 1..4) for (column in 6..7) add(GridCell(row, column))
        }
        // A sparse, connected-looking silhouette can occupy less than the old 10% per-cell gate.
        for (cell in footprint) for (y in 9..14) for (x in 9..14) {
            val px = cell.column * 24 + x
            val py = cell.row * 24 + y
            val offset = (py * 216 + px) * 4
            bytes[offset] = 70; bytes[offset + 1] = 74; bytes[offset + 2] = 72
        }
        val cells = (0 until 5).flatMap { row -> (0 until 9).map { column ->
            val cell = GridCell(row, column)
            BoardCellObservation(row, column, if (cell == GridCell(1, 6) || cell == GridCell(4, 7))
                BoardCellState.OPEN_FRAGMENT else BoardCellState.OPEN_EMPTY)
        } }
        val shape = ItemShape(4, 2, buildSet {
            for (row in 0 until 4) for (column in 0 until 2) add(ShapeCell(row, column))
        })
        val empty = RgbaPatch(1, 1, byteArrayOf(0, 0, 0, 0))
        val card = ItemCardRecognition(1, board.region, board.region, board.region, board.region,
            shape, 0, true, 1.0, empty)

        val result = WholeBoardSilhouetteMatcher().match(
            RgbaFrame(216, 120, 216 * 4, bytes, 0), board, cells, listOf(card))

        assertEquals(footprint, result.objects.single().observedCells)
        assertTrue(result.objects.single().evidence.contains("Finish 形状约束"))
        assertTrue(result.cells.filter { GridCell(it.row, it.column) in footprint }
            .all { it.state == BoardCellState.OPEN_OBJECT })

        val recognized = BoardObjectRecognizer().recognizeWithCorrections(
            RgbaFrame(216, 120, 216 * 4, bytes, 0), board, cells, listOf(card))
        assertEquals(1, recognized.objects.size)
        assertTrue(recognized.objects.none { it.phase == com.bagridmaster.app.analysis.BoardObjectPhase.LIT })
        assertEquals(footprint, recognized.objects.single().observedCells)
    }

    @Test fun finishShapeDoesNotInventASilhouetteOnUniformBackground() {
        val bytes = ByteArray(216 * 120 * 4)
        for (offset in bytes.indices step 4) {
            bytes[offset] = 220.toByte(); bytes[offset + 1] = 220.toByte()
            bytes[offset + 2] = 220.toByte(); bytes[offset + 3] = 255.toByte()
        }
        val cells = (0 until 5).flatMap { row -> (0 until 9).map { column ->
            BoardCellObservation(row, column, if ((row == 0 && column == 0) || (row == 3 && column == 1))
                BoardCellState.OPEN_FRAGMENT else BoardCellState.OPEN_EMPTY)
        } }
        val shape = ItemShape(4, 2, buildSet {
            for (row in 0 until 4) for (column in 0 until 2) add(ShapeCell(row, column))
        })
        val card = ItemCardRecognition(1, board.region, board.region, board.region, board.region,
            shape, 0, true, 1.0, RgbaPatch(1, 1, byteArrayOf(0, 0, 0, 0)))

        val result = WholeBoardSilhouetteMatcher().match(
            RgbaFrame(216, 120, 216 * 4, bytes, 0), board, cells, listOf(card))

        assertTrue(result.objects.isEmpty())
    }
}
