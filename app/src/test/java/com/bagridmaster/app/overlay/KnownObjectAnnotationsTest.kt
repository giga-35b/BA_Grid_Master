package com.bagridmaster.app.overlay

import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.vision.BoardCellState
import org.junit.Assert.*
import org.junit.Test

class KnownObjectAnnotationsTest {
    private val board = BoardGeometry(ScreenRegion(100, 200, 1000, 700), 5, 9, .99)
    private fun obj(phase: BoardObjectPhase, type: Int?, vararg cells: GridCell) =
        BoardObjectObservation("internal-id", phase, cells.toSet(), listOfNotNull(type), .9, "test")

    @Test fun completedStripIsOneRectangleAndOneChineseLabel() {
        val item = obj(BoardObjectPhase.COMPLETED, 0, GridCell(0, 1), GridCell(1, 1), GridCell(2, 1))
        val mark = knownObjectAnnotations(board, listOf(item), emptyList()).single()
        assertEquals("a 全开", mark.label)
        assertEquals(ScreenRegion(200, 200, 300, 500), mark.region)
    }

    @Test fun completedSquareAndAdjacentObjectRemainSeparate() {
        val square = obj(BoardObjectPhase.COMPLETED, 2, GridCell(0, 0), GridCell(0, 1), GridCell(1, 0), GridCell(1, 1))
        val strip = obj(BoardObjectPhase.COMPLETED, 1, GridCell(2, 0), GridCell(2, 1))
        val marks = knownObjectAnnotations(board, listOf(square, strip), emptyList())
        assertEquals(listOf("c 全开", "b 全开"), marks.map { it.label })
        assertEquals(ScreenRegion(100, 200, 300, 400), marks.first().region)
    }

    @Test fun litLabelsOnlyCoverObservedCellsNotMatchedHiddenFootprint() {
        val item = obj(BoardObjectPhase.LIT, 0, GridCell(1, 1)).copy(
            localMatch = LocalTemplateMatch(true, listOf(
                TemplatePlacementMatch(0, GridCell(0, 1), 3, 1, 0, .9, .9, .9)), .2, "test"))
        val mark = knownObjectAnnotations(board, listOf(item), emptyList()).single()
        assertEquals("a 点亮", mark.label)
        assertEquals(board.cellRegion(1, 1), mark.region)
    }

    @Test fun onlyOpenEmptyCellsGetGrayEmptyLabel() {
        val cells = BoardCellState.entries.mapIndexed { i, state -> BoardCellObservation(0, i, state) }
        val mark = knownObjectAnnotations(board, emptyList(), cells).single()
        assertEquals("空", mark.label)
        assertEquals(0xFFA0A0A0.toInt(), mark.color)
        assertEquals(board.cellRegion(0, BoardCellState.OPEN_EMPTY.ordinal), mark.region)
    }

    @Test fun uncertainTypesAreNotInventedAndOutOfBoundsIgnored() {
        val unknown = obj(BoardObjectPhase.LIT, null, GridCell(0, 0), GridCell(-1, 0))
        val ambiguous = unknown.copy(possibleItemIndices = listOf(0, 2))
        assertEquals("未知 点亮", knownObjectAnnotations(board, listOf(unknown), emptyList()).single().label)
        assertEquals("a/c 点亮", knownObjectAnnotations(board, listOf(ambiguous), emptyList()).single().label)
    }

    @Test fun malformedNonRectangularGroupDoesNotCoverHiddenHoles() {
        val item = obj(BoardObjectPhase.COMPLETED, null, GridCell(0, 0), GridCell(1, 0), GridCell(1, 1))
        val marks = knownObjectAnnotations(board, listOf(item), emptyList())
        assertEquals(3, marks.size)
        assertTrue(marks.all { it.region.width == 100 && it.region.height == 100 })
        assertEquals(1, marks.count { it.label.isNotEmpty() })
    }
}
