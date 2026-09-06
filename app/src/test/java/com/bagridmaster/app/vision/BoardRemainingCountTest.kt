package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.RecognitionGeometry
import com.bagridmaster.app.analysis.ScreenRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardRemainingCountTest {
    @Test fun parsesOnlyOnePlausibleNOver45Token() {
        assertEquals(39, parseBoardRemainingCount("剩余格子数量: 39/45")?.remaining)
        assertEquals(7, parseBoardRemainingCount("７／４５")?.remaining)
        assertNull(parseBoardRemainingCount("89/45"))
        assertNull(parseBoardRemainingCount("39/4S"))
        assertNull(parseBoardRemainingCount("39/45 38/45"))
    }

    @Test fun highConfidenceCountMovesOnlyVisuallyAmbiguousCells() {
        val cells = MutableList(38) { index -> cell(index, BoardCellState.CLOSED, 0.8, 0.1) }
        cells += cell(38, BoardCellState.OPEN_EMPTY, 0.42, 0.54)
        repeat(6) { cells += cell(39 + it, BoardCellState.OPEN_FRAGMENT, 0.0, 0.0) }
        val adjusted = GameVisionDetector().applyRemainingCountPrior(detection(cells),
            BoardRemainingCountEvidence(39, 45, 0.92, "39/45"))
        assertEquals(39, adjusted.boardCells.count { it.state.isUnopened })

        val strongEmpty = cells.toMutableList().also {
            it[38] = cell(38, BoardCellState.OPEN_EMPTY, 0.05, 0.90)
        }
        val protected = GameVisionDetector().applyRemainingCountPrior(detection(strongEmpty),
            BoardRemainingCountEvidence(39, 45, 0.92, "39/45"))
        assertEquals(38, protected.boardCells.count { it.state.isUnopened })
    }

    @Test fun lowConfidenceCountIsIgnored() {
        val cells = MutableList(38) { index -> cell(index, BoardCellState.CLOSED, 0.8, 0.1) }
        cells += cell(38, BoardCellState.OPEN_EMPTY, 0.5, 0.5)
        repeat(6) { cells += cell(39 + it, BoardCellState.OPEN_FRAGMENT, 0.0, 0.0) }
        val adjusted = GameVisionDetector().applyRemainingCountPrior(detection(cells),
            BoardRemainingCountEvidence(39, 45, 0.40, "39/45"))
        assertEquals(38, adjusted.boardCells.count { it.state.isUnopened })
    }

    private fun cell(index: Int, state: BoardCellState, closed: Double, empty: Double) =
        BoardCellObservation(index / 9, index % 9, state,
            classification = CellClassificationEvidence(closed, empty, if (state == BoardCellState.OPEN_FRAGMENT) 1.0 else 0.0))

    private fun detection(cells: List<BoardCellObservation>) = GameVisionDetection(
        geometry = RecognitionGeometry(BoardGeometry(ScreenRegion(0, 0, 90, 50), 5, 9, 1.0), null, "test"),
        itemCards = emptyList(), boardContentScore = 1.0, boardCells = cells,
    )
}
