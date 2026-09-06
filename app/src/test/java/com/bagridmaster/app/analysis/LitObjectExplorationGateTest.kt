package com.bagridmaster.app.analysis

import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.vision.*
import org.junit.Assert.*
import org.junit.Test

class LitObjectExplorationGateTest {
    private val geometry = BoardGeometry(ScreenRegion(0, 0, 900, 500), 5, 9, 1.0)
    private val gate = LitObjectExplorationGate()
    private val planner = FragmentCompletionPlanner()
    private val solver = BoardStrategySolver()
    private fun p(address: String) = GridCell(address[1] - '1', address[0] - 'A')
    private fun area(vararg addresses: String) = addresses.map(::p).toSet()
    private fun cells(lit: Set<GridCell> = emptySet(), completed: Set<GridCell> = emptySet(),
                      empty: Set<GridCell> = emptySet(), selected: Set<GridCell> = emptySet()) =
        (0 until 5).flatMap { r -> (0 until 9).map { c ->
            val cell = GridCell(r, c)
            BoardCellObservation(r, c, when (cell) {
                in lit -> BoardCellState.OPEN_FRAGMENT
                in completed -> BoardCellState.OPEN_OBJECT
                in empty -> BoardCellState.OPEN_EMPTY
                in selected -> BoardCellState.SELECTED
                else -> BoardCellState.CLOSED
            })
        } }
    private fun card(index: Int, rows: Int, columns: Int, count: Int) = ItemCardRecognition(index,
        geometry.region, geometry.region, geometry.region, geometry.region,
        ItemShape(rows, columns, (0 until rows).flatMap { r -> (0 until columns).map { c -> ShapeCell(r, c) } }.toSet()),
        count, count == 0, 1.0, RgbaPatch(1, 1, byteArrayOf(0, 0, 0, 0)))
    private fun lit(id: String, source: String, index: Int, matched: Set<GridCell>? = null): BoardObjectObservation {
        val match = matched?.let {
            val r = it.minOf { cell -> cell.row }; val c = it.minOf { cell -> cell.column }
            LocalTemplateMatch(true, listOf(TemplatePlacementMatch(index, GridCell(r, c),
                it.maxOf { cell -> cell.row } - r + 1, it.maxOf { cell -> cell.column } - c + 1,
                0, 0.95, 0.95, 0.95)), 0.2, "test")
        }
        return BoardObjectObservation(id, BoardObjectPhase.LIT, setOf(p(source)), listOf(index), 0.99, "test", match)
    }
    private fun explore(prepared: PreparedExploration, completion: FragmentCompletion, algorithm: StrategyAlgorithm = StrategyAlgorithm.GREEDY): CellRecommendation? =
        if (!prepared.allowed) null else solver.recommend(prepared.cells, prepared.inventory, algorithm,
            completion.recommendations.mapTo(mutableSetOf()) { GridCell(it.row, it.column) }, prepared.knownLitFootprints)

    @Test fun matchedSeaweedProducesSameExplorationBeforeAndAfterActuallyOpeningIt() {
        val inventory = listOf(card(0, 1, 3, 3), card(1, 1, 4, 3), card(2, 2, 3, 1))
        val footprint = area("B4", "C4", "D4", "E4")
        val before = cells(area("C4"), area("B1", "B2", "B3"), area("A3"), area("D4"))
        val completion = planner.complete(geometry, before, inventory, listOf(lit("L1", "C4", 1, footprint)), emptyList())
        assertEquals(area("B4", "D4", "E4"), completion.recommendations.map { GridCell(it.row, it.column) }.toSet())
        val prepared = gate.prepare(geometry, before, inventory, completion)
        assertTrue(prepared.explanation, prepared.allowed)
        assertEquals(2, prepared.inventory[1].remainingCount)
        val after = cells(completed = area("B1", "B2", "B3") + footprint, empty = area("A3"))
        assertEquals(after, prepared.cells)
        for (algorithm in StrategyAlgorithm.entries) {
            val recommended = explore(prepared, completion, algorithm)
            assertEquals(solver.recommend(after, prepared.inventory, algorithm), recommended)
            if (algorithm == StrategyAlgorithm.GREEDY) assertEquals(p("C5"), recommended?.let { GridCell(it.row, it.column) })
        }
        assertEquals(3, inventory[1].remainingCount) // No mutation of observations.
        assertEquals(BoardCellState.SELECTED, before.single { it.row == 3 && it.column == 3 }.state)
    }

    @Test fun squareCornerExtensionCompletesOtherThreeCellsAndAllowsExploration() {
        val inventory = listOf(card(0, 2, 2, 2), card(1, 1, 3, 1), card(2, 2, 3, 0))
        val board = cells(area("B2"))
        val completion = planner.complete(geometry, board, inventory, listOf(lit("L1", "B2", 0)),
            listOf(FragmentEdgeEvidence(p("B2"), 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)))
        assertEquals(area("B1", "C1", "C2"), completion.recommendations.map { GridCell(it.row, it.column) }.toSet())
        assertEquals(area("B1", "C1", "B2", "C2"), completion.fullCompletions.single().footprints.single())
        assertTrue(completion.fullCompletions.single().basis.contains("情况2"))
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertTrue(prepared.allowed)
        assertNotNull(explore(prepared, completion))
    }

    @Test fun linearHeadWithoutReliableMatchingOnlyContinuesOneCellAndDoesNotExplore() {
        val inventory = listOf(card(0, 1, 3, 2), card(1, 1, 4, 1), card(2, 2, 3, 1))
        val board = cells(area("B2"))
        val completion = planner.complete(geometry, board, inventory, listOf(lit("L1", "B2", 0)),
            listOf(FragmentEdgeEvidence(p("B2"), 0.0, 0.0, 0.5, 0.0, 0.0, 0.0)))
        assertEquals(listOf(p("B3")), completion.recommendations.map { GridCell(it.row, it.column) })
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertFalse(prepared.allowed)
        assertNull(explore(prepared, completion))
        assertTrue(prepared.explanation.contains("L1"))
    }

    @Test fun twoLitObjectsOneMatchedOneIncompleteMustStopAllExploration() {
        val inventory = listOf(card(0, 1, 3, 2), card(1, 1, 4, 2), card(2, 2, 3, 1))
        val board = cells(area("B2", "F2"))
        val completion = planner.complete(geometry, board, inventory,
            listOf(lit("L1", "B2", 0, area("B1", "B2", "B3")), lit("L2", "F2", 1)),
            listOf(FragmentEdgeEvidence(p("F2"), 0.0, 0.0, 0.5, 0.0, 0.0, 0.0)))
        assertEquals(1, completion.fullCompletions.size)
        assertEquals(area("B1", "B3", "F3"), completion.recommendations.map { GridCell(it.row, it.column) }.toSet())
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertFalse(prepared.allowed)
        assertTrue(prepared.explanation.contains("L2"))
    }

    @Test fun twoLitObjectsMatchedAndCompleteExtensionTogetherAllowExploration() {
        val inventory = listOf(card(0, 1, 3, 2), card(1, 2, 2, 2), card(2, 2, 3, 0))
        val board = cells(area("B2", "F2"))
        val completion = planner.complete(geometry, board, inventory,
            listOf(lit("L1", "B2", 0, area("B1", "B2", "B3")), lit("L2", "F2", 1)),
            listOf(FragmentEdgeEvidence(p("F2"), 0.5, 0.5, 0.0, 0.0, 0.0, 0.0)))
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertTrue(prepared.explanation, prepared.allowed)
        assertEquals(listOf(1, 1, 0), prepared.inventory.map { it.remainingCount })
        assertNotNull(explore(prepared, completion))
    }

    @Test fun allRemainingObjectsLitStillRecommendsCompletionButNoExploration() {
        val inventory = listOf(card(0, 1, 3, 1), card(1, 1, 4, 0), card(2, 2, 3, 0))
        val board = cells(area("B2"))
        val completion = planner.complete(geometry, board, inventory,
            listOf(lit("L1", "B2", 0, area("B1", "B2", "B3"))), emptyList())
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertFalse(prepared.allowed)
        assertTrue(prepared.explanation.contains("均已点亮"))
        assertEquals(2, completion.recommendations.size)
    }

    @Test fun multipleSameKindObjectsAreDeductedExactlyOnceEach() {
        val inventory = listOf(card(0, 1, 3, 3), card(1, 1, 4, 0), card(2, 2, 3, 0))
        val board = cells(area("B2", "F2"))
        val completion = planner.complete(geometry, board, inventory,
            listOf(lit("L1", "B2", 0, area("B1", "B2", "B3")), lit("L2", "F2", 0, area("F1", "F2", "F3"))), emptyList())
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertTrue(prepared.allowed)
        assertEquals(1, prepared.inventory[0].remainingCount)
        val again = gate.prepare(geometry, prepared.cells, prepared.inventory, FragmentCompletion(emptyList(), emptyList()))
        assertEquals(1, again.inventory[0].remainingCount)
        val impossible = gate.prepare(geometry, board, inventory.map { if (it.index == 0) it.copy(remainingCount = 1) else it }, completion)
        assertFalse(impossible.allowed)
    }

    @Test fun disconnectedFragmentsWithTheSameExactFootprintAreOnePhysicalObject() {
        val inventory = listOf(card(0, 1, 3, 1), card(1, 4, 2, 1), card(2, 3, 3, 1))
        val footprint = area("H1", "I1", "H2", "I2", "H3", "I3", "H4", "I4")
        val board = cells(area("H2", "H4"))
        val suggestions = (footprint - area("H2", "H4")).map {
            CellRecommendation(it.row, it.column, 0.1, "test")
        }
        val objects = listOf(lit("L1", "H2", 1), lit("L2", "H4", 1))
        val completion = FragmentCompletion(objects, suggestions, listOf(
            PlannedLitObject("L1", 1, listOf(footprint), "test"),
            PlannedLitObject("L2", 1, listOf(footprint), "test"),
        ))

        val prepared = gate.prepare(geometry, board, inventory, completion)

        assertTrue(prepared.explanation, prepared.allowed)
        assertEquals(0, prepared.inventory.single { it.index == 1 }.remainingCount)
        assertTrue(prepared.explanation.contains("L1+L2"))
        assertNotNull(explore(prepared, completion))
    }

    @Test fun conflictingHiddenFootprintsCannotPassTheAllObjectsGate() {
        val inventory = listOf(card(0, 1, 3, 3), card(1, 1, 4, 0), card(2, 2, 3, 0))
        val board = cells(area("A2", "E2"))
        val completion = planner.complete(geometry, board, inventory,
            listOf(lit("L1", "A2", 0, area("A2", "B2", "C2")), lit("L2", "E2", 0, area("C2", "D2", "E2"))), emptyList())
        assertFalse(gate.prepare(geometry, board, inventory, completion).allowed)
    }

    @Test fun noLitObjectsPreserveBlankBoardOpeningBook() {
        val inventory = listOf(card(0, 1, 3, 4), card(1, 1, 4, 3), card(2, 2, 3, 1))
        val completion = FragmentCompletion(emptyList(), emptyList())
        val prepared = gate.prepare(geometry, cells(), inventory, completion)
        assertTrue(prepared.allowed)
        assertEquals(p("A3"), explore(prepared, completion)?.let { GridCell(it.row, it.column) })
        assertFalse(gate.prepare(geometry, cells(area("B2")), inventory, completion).allowed)
    }

    @Test fun coveredAlternativeFootprintsAreSampledSeparatelyNotBlockedAsTheirUnion() {
        val inventory = listOf(card(0, 1, 3, 1), card(1, 1, 2, 1), card(2, 2, 3, 0))
        val board = cells(area("E3"))
        val footprints = listOf(area("C3", "D3", "E3"), area("E3", "F3", "G3"))
        val suggestions = (footprints.flatten().toSet() - p("E3")).map { CellRecommendation(it.row, it.column, 0.2, "test") }
        val completion = FragmentCompletion(listOf(lit("L1", "E3", 0)), suggestions,
            listOf(PlannedLitObject("L1", 0, footprints, "情况2")))
        val prepared = gate.prepare(geometry, board, inventory, completion)
        assertTrue(prepared.allowed)
        assertEquals(board, prepared.cells)
        assertEquals(footprints, prepared.knownLitFootprints.single())
        assertEquals(0, prepared.inventory[0].remainingCount)
        val result = checkNotNull(explore(prepared, completion))
        assertFalse(GridCell(result.row, result.column) in footprints.flatten())
    }

    @Test fun alternativeLayoutsKeepSpaceForUndiscoveredObjectsInsteadOfBlockingTheUnion() {
        val inventory = listOf(card(0, 1, 2, 1), card(1, 1, 1, 1), card(2, 2, 3, 0))
        val available = area("A1", "B1", "C1", "D1")
        val board = cells(area("B1")).map {
            if (GridCell(it.row, it.column) !in available) it.copy(state = BoardCellState.OPEN_EMPTY) else it
        }
        val footprints = listOf(area("A1", "B1"), area("B1", "C1"))
        val completion = FragmentCompletion(listOf(lit("L1", "B1", 0)),
            area("A1", "C1").map { CellRecommendation(it.row, it.column, 0.5, "test") },
            listOf(PlannedLitObject("L1", 0, footprints, "情况2")))
        val prepared = gate.prepare(geometry, board, inventory, completion)
        val result = checkNotNull(explore(prepared, completion))
        assertEquals(p("D1"), GridCell(result.row, result.column))
        // Four equally likely joint layouts: undiscovered A1 / C1 / D1 / D1.
        // Blocking A1+B1+C1 as a union would incorrectly assign 100% hit to D1.
        assertEquals(0.5, 1 - result.missProbability, 0.04)
    }
}
