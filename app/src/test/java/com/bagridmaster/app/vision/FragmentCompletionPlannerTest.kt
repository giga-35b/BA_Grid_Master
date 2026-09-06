package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import org.junit.Assert.*
import org.junit.Test

class FragmentCompletionPlannerTest {
    private val board = BoardGeometry(ScreenRegion(0, 0, 900, 500), 5, 9, 1.0)
    private val source = GridCell(1, 1)
    private val item = BoardObjectObservation("L1", BoardObjectPhase.LIT, setOf(source), listOf(0), 0.99, "test")
    private val cells = (0 until 5).flatMap { r -> (0 until 9).map { c ->
        BoardCellObservation(r, c, if (GridCell(r, c) == source) BoardCellState.OPEN_FRAGMENT else BoardCellState.CLOSED)
    } }
    private fun card(rows: Int, columns: Int) = ItemCardRecognition(0, board.region, board.region, board.region, board.region,
        ItemShape(rows, columns, (0 until rows).flatMap { r -> (0 until columns).map { c -> ShapeCell(r, c) } }.toSet()),
        1, false, 1.0, RgbaPatch(1, 1, byteArrayOf(255.toByte(), 200.toByte(), 0, 255.toByte())))

    @Test fun oneByThreeNeverAddsPerpendicularOrDiagonalCells() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(1, 3)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.4, 0.21, 0.4, 0.0, 0.24, 0.0)))
        assertEquals(setOf("B1", "B3"), result.recommendations.map { cellAddress(it.row, it.column) }.toSet())
    }

    @Test fun ambiguousLinearDirectionDoesNotRecommendBothAxes() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(1, 3)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.4, 0.4, 0.4, 0.4, 0.0, 0.0)))
        assertTrue(result.recommendations.isEmpty())
        assertTrue(result.objects.single().completionEvidence.contains("方向证据冲突"))
    }

    @Test fun trueNeckContactOutranksWideHeadPrincipalAxis() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(1, 3)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.0, 0.0, 0.30, 0.0, 0.0, 0.24)))
        assertEquals(setOf("B3"), result.recommendations.map { cellAddress(it.row, it.column) }.toSet())
        assertTrue(result.fullCompletions.isEmpty())
    }

    @Test fun rectangleStillAddsSupportedDiagonal() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(2, 2)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)))
        assertEquals(setOf("B1", "C2", "C1"), result.recommendations.map { cellAddress(it.row, it.column) }.toSet())
        assertEquals(setOf("B1", "C1", "B2", "C2"), result.fullCompletions.single().footprints.single().map { cellAddress(it.row, it.column) }.toSet())
    }

    @Test fun diagonalCannotCrossKnownEmptyCellEvenWithTwoStrongDirections() {
        val state = cells.map { if (it.row == 0 && it.column == 2) it.copy(state = BoardCellState.OPEN_EMPTY) else it }
        val result = FragmentCompletionPlanner().complete(board, state, listOf(card(2, 2)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)))
        assertFalse(result.recommendations.any { cellAddress(it.row, it.column) == "C1" })
        assertTrue(result.fullCompletions.isEmpty())
    }

    @Test fun noShapeMeansNoForcedContinuation() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(1, 3).copy(shape = null)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0)))
        assertTrue(result.recommendations.isEmpty())
    }

    @Test fun selectedTargetStillGetsMatchedCompletionAndEmptyCellsBlockPlacements() {
        val state = cells.map { if (it.row == 0 && it.column == 1) it.copy(state = BoardCellState.SELECTED) else it }
        val placements = legalObjectPlacements(board, state.associate { GridCell(it.row, it.column) to it.state }, setOf(source), listOf(card(1, 3)))
        assertTrue(placements.any { it.origin == GridCell(0, 1) && it.columns == 1 })
        val blocked = state.map { if (it.row == 0 && it.column == 1) it.copy(state = BoardCellState.OPEN_EMPTY) else it }
        assertFalse(legalObjectPlacements(board, blocked.associate { GridCell(it.row, it.column) to it.state }, setOf(source), listOf(card(1, 3)))
            .any { it.origin == GridCell(0, 1) && it.columns == 1 })
    }

    @Test fun completeCoverageOfSeveralSquarePlacementsRetainsAllAlternatives() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(2, 2)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0)))
        assertEquals(8, result.recommendations.size)
        assertEquals(4, result.fullCompletions.single().footprints.size)
        val coverage = result.recommendations.map { GridCell(it.row, it.column) }.toSet() + source
        assertTrue(result.fullCompletions.single().footprints.all { coverage.containsAll(it) })
    }

    @Test fun sameNumberOfSuggestionsIsNotProofOfCompletingAnUnresolvedShape() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(2, 3)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0)))
        assertTrue(result.recommendations.size >= 5)
        assertTrue("Some 2x3 placements extend beyond the recommended neighborhood", result.fullCompletions.isEmpty())
    }

    @Test fun fullCoverageWithUncertainTypeCannotInventWhichInventoryCountToDeduct() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(2, 2), card(2, 2).copy(index = 1)),
            listOf(item.copy(possibleItemIndices = listOf(0, 1))),
            listOf(FragmentEdgeEvidence(source, 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)))
        assertEquals(3, result.recommendations.size)
        assertTrue(result.fullCompletions.isEmpty())
    }

    @Test fun squareDirectionsLocateEveryCornerEdgeAndCentreWithoutTemplateRotation() {
        for (size in listOf(2, 3)) for (r in 0 until size) for (c in 0 until size) {
            val origin = GridCell(1, 3)
            val fragment = GridCell(origin.row + r, origin.column + c)
            val state = cells.map { it.copy(state = if (GridCell(it.row, it.column) == fragment)
                BoardCellState.OPEN_FRAGMENT else BoardCellState.CLOSED) }
            val edge = FragmentEdgeEvidence(fragment,
                if (r > 0) 0.5 else 0.0, if (c < size - 1) 0.5 else 0.0,
                if (r < size - 1) 0.5 else 0.0, if (c > 0) 0.5 else 0.0, 0.0, 0.0)
            val result = FragmentCompletionPlanner().complete(board, state, listOf(card(size, size)),
                listOf(item.copy(observedCells = setOf(fragment))), listOf(edge))
            val expected = (0 until size).flatMap { dr -> (0 until size).map { dc -> GridCell(origin.row + dr, origin.column + dc) } }.toSet()
            assertEquals("$size x $size fragment=($r,$c)", expected - fragment,
                result.recommendations.map { GridCell(it.row, it.column) }.toSet())
            assertEquals(expected, result.fullCompletions.single().footprints.single())
            assertTrue(result.recommendations.all { it.strategy.contains("正方形方向") })
        }
    }

    @Test fun squareMissingOneAxisDoesNotInventDistantCells() {
        val result = FragmentCompletionPlanner().complete(board, cells, listOf(card(3, 3)), listOf(item),
            listOf(FragmentEdgeEvidence(source, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0)))
        assertEquals(setOf("B3"), result.recommendations.map { cellAddress(it.row, it.column) }.toSet())
        assertTrue(result.fullCompletions.isEmpty())
    }

    @Test fun squareDirectionsCannotInventPlacementAcrossOtherObservedObject() {
        val other = item.copy(id = "C1", phase = BoardObjectPhase.COMPLETED, observedCells = setOf(GridCell(3, 3)))
        val state = cells.map { if (GridCell(it.row, it.column) in other.observedCells) it.copy(state = BoardCellState.OPEN_OBJECT) else it }
        val result = FragmentCompletionPlanner().complete(board, state, listOf(card(3, 3)), listOf(item, other),
            listOf(FragmentEdgeEvidence(source, 0.0, 0.5, 0.5, 0.0, 0.0, 0.0)))
        assertTrue(result.fullCompletions.isEmpty())
        assertFalse(result.recommendations.any { GridCell(it.row, it.column) in other.observedCells })
    }

    @Test fun disconnectedFragmentsMergeUsingTheirJointMeasuredEdges() {
        fun candidate(origin: GridCell, rows: Int, columns: Int, score: Double) =
            TemplatePlacementMatch(1, origin, rows, columns, 90, score, score, score)
        val wrongLeftward = candidate(GridCell(0, 6), 4, 2, 0.56)
        val correctRightward = candidate(GridCell(0, 7), 4, 2, 0.69)
        val h2 = item.copy(id = "L2", observedCells = setOf(GridCell(1, 7)), possibleItemIndices = listOf(1),
            localMatch = LocalTemplateMatch(false, listOf(
                correctRightward,
                candidate(GridCell(1, 7), 4, 2, 0.56),
                wrongLeftward,
            ), 0.12, "test"))
        val h4 = item.copy(id = "L4", observedCells = setOf(GridCell(3, 7)), possibleItemIndices = listOf(1),
            localMatch = LocalTemplateMatch(false, listOf(
                candidate(GridCell(3, 4), 2, 4, 0.63),
                wrongLeftward.copy(similarity = 0.63),
                candidate(GridCell(2, 5), 2, 4, 0.60),
            ), 0.0, "test"))

        val state = (0 until 5).flatMap { r -> (0 until 9).map { c ->
            val cell = GridCell(r, c)
            BoardCellObservation(r, c, if (cell in setOf(GridCell(1, 7), GridCell(3, 7)))
                BoardCellState.OPEN_FRAGMENT else BoardCellState.CLOSED)
        } }
        val edges = listOf(
            FragmentEdgeEvidence(GridCell(1, 7), 0.73, 1.0, 1.0, 0.0, 0.0, 0.0),
            FragmentEdgeEvidence(GridCell(3, 7), 0.97, 1.0, 0.0, 0.0, 0.0, 0.0),
        )
        val merged = FragmentCompletionPlanner().mergeDisconnectedLitObjects(
            listOf(h2, h4), board, state, listOf(card(4, 2).copy(index = 1)), edges)

        assertEquals(1, merged.size)
        assertEquals(setOf(GridCell(1, 7), GridCell(3, 7)), merged.single().observedCells)
        assertEquals(correctRightward.cells, merged.single().localMatch?.candidates?.single()?.cells)
        assertTrue(merged.single().localMatch?.accepted == true)
    }
}
