package com.bagridmaster.app.analysis

import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.vision.BoardCellState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardStrategySolverTest {
    private val solver = BoardStrategySolver()

    @Test
    fun everyKnownInventoryUsesAnOpeningBookEntryOnBlankBoard() {
        for (configuration in InventoryConfiguration.entries) {
            val recommendation = solver.recommend(
                cells = board(),
                cards = cards(configuration),
                algorithm = StrategyAlgorithm.LIMITED_LOOKAHEAD,
            )
            assertNotNull(configuration.name, recommendation)
            assertEquals(PresetOpeningBook[configuration].first.row, recommendation?.row)
            assertEquals(PresetOpeningBook[configuration].first.column, recommendation?.column)
            assertTrue(recommendation?.strategy?.contains("预计算") == true)
        }
    }

    @Test
    fun openingBookMatchesOfflinePosteriorPolicy() {
        for (configuration in InventoryConfiguration.entries) {
            for (algorithm in StrategyAlgorithm.entries) {
                val initialBoard = board()
                val first = solver.rankComputed(
                    initialBoard,
                    configuration.items,
                    algorithm,
                    sampleBudget = 25_000,
                ).first()
                val firstCell = GridCell(first.row, first.column)
                val second = solver.rankComputed(
                    board(mapOf(firstCell to BoardCellState.OPEN_EMPTY)),
                    configuration.items,
                    algorithm,
                    sampleBudget = 25_000,
                ).first()
                val entry = PresetOpeningBook.get(configuration, algorithm)
                assertEquals(address(first), address(entry.first))
                assertEquals(address(second), address(entry.secondAfterEmpty))
                assertEquals(first.missProbability, entry.firstMissProbability, 0.00000001)
                assertEquals(second.missProbability, entry.secondMissProbability, 0.00000001)
            }
        }
    }

    @Test
    fun firstPresetMissUsesSecondPresetWithoutLiveSearch() {
        for (configuration in InventoryConfiguration.entries) {
            val first = PresetOpeningBook[configuration].first
            val recommendation = solver.recommend(
                cells = board(mapOf(first to BoardCellState.OPEN_EMPTY)),
                cards = cards(configuration),
                algorithm = StrategyAlgorithm.LIMITED_LOOKAHEAD,
            )
            assertEquals(PresetOpeningBook[configuration].secondAfterEmpty.row, recommendation?.row)
            assertEquals(PresetOpeningBook[configuration].secondAfterEmpty.column, recommendation?.column)
            assertTrue(recommendation?.strategy?.contains("预计算") == true)
        }
    }

    @Test
    fun selectedCellsHaveExactlyTheSamePolicyAsUnopenedCells() {
        for (algorithm in StrategyAlgorithm.entries) {
            for (configuration in InventoryConfiguration.entries) {
                val first = PresetOpeningBook.get(configuration, algorithm).first
                val selectedBoard = board(mapOf(first to BoardCellState.SELECTED, GridCell(2, 4) to BoardCellState.SELECTED))
                assertEquals(solver.recommend(board(), cards(configuration), algorithm), solver.recommend(selectedBoard, cards(configuration), algorithm))
                val second = PresetOpeningBook.get(configuration, algorithm).secondAfterEmpty
                val emptyBoard = board(mapOf(first to BoardCellState.OPEN_EMPTY))
                val selectedSecond = board(mapOf(first to BoardCellState.OPEN_EMPTY, second to BoardCellState.SELECTED))
                assertEquals(solver.recommend(emptyBoard, cards(configuration), algorithm), solver.recommend(selectedSecond, cards(configuration), algorithm))
            }
        }
    }

    @Test
    fun selectionsAlsoRemainUnopenedDuringLivePosteriorSearch() {
        val states = mapOf(GridCell(0, 4) to BoardCellState.OPEN_EMPTY, GridCell(2, 4) to BoardCellState.OPEN_FRAGMENT)
        for (algorithm in StrategyAlgorithm.entries) {
            assertEquals(
                solver.recommend(board(states), cards(InventoryConfiguration.ROUND_1), algorithm),
                solver.recommend(board(states + (GridCell(1, 4) to BoardCellState.SELECTED)), cards(InventoryConfiguration.ROUND_1), algorithm),
            )
        }
    }

    @Test
    fun noExtraExplorationWhenEveryRemainingItemHasBeenLit() {
        val inventory = cards(InventoryConfiguration.ROUND_1).mapIndexed { index, card ->
            card.copy(remainingCount = if (index == 0) 1 else 0, isFinished = index != 0)
        }
        val recommendation = solver.recommend(
            cells = board(mapOf(GridCell(2, 4) to BoardCellState.OPEN_FRAGMENT)),
            cards = inventory,
            algorithm = StrategyAlgorithm.LIMITED_LOOKAHEAD,
        )
        assertNull(recommendation)
    }

    @Test
    fun differentEmptyCellDoesNotUseSecondStepPreset() {
        val recommendation = checkNotNull(
            solver.recommend(
                cells = board(mapOf(GridCell(0, 4) to BoardCellState.OPEN_EMPTY)),
                cards = cards(InventoryConfiguration.ROUND_1),
                algorithm = StrategyAlgorithm.GREEDY,
            ),
        )
        assertTrue(!recommendation.strategy.contains("预计算"))
    }

    @Test
    fun missingCountsDoNotInventInitialInventory() {
        val inventory = cards(InventoryConfiguration.ROUND_1).map { it.copy(remainingCount = null) }
        assertNull(solver.recommend(board(), inventory, StrategyAlgorithm.GREEDY))
    }

    @Test
    fun unreadableActiveCardDoesNotSilentlyOmitItsObjects() {
        val inventory = cards(InventoryConfiguration.ROUND_1).mapIndexed { index, card ->
            if (index == 0) card.copy(shape = null) else card
        }
        assertNull(solver.recommend(board(), inventory, StrategyAlgorithm.GREEDY))
    }

    @Test
    fun completedCardsCanLoseTheirFootprintWithoutBlockingRemainingItems() {
        val inventory = cards(InventoryConfiguration.ROUND_1).mapIndexed { index, card ->
            if (index == 0) card.copy(remainingCount = 1)
            else card.copy(shape = null, remainingCount = 0, isFinished = true)
        }
        val recommendation = solver.recommend(board(), inventory, StrategyAlgorithm.GREEDY)
        assertNotNull(recommendation)
        assertTrue(recommendation?.strategy?.contains("预计算") == false)
    }

    @Test
    fun greedyAndLookaheadOnlyChooseClosedCells() {
        val states = mapOf(
            GridCell(2, 4) to BoardCellState.OPEN_EMPTY,
            GridCell(2, 1) to BoardCellState.OPEN_EMPTY,
            GridCell(1, 4) to BoardCellState.OPEN_OBJECT,
        )
        val cells = board(states)
        val cards = cards(InventoryConfiguration.ROUND_3)
        val greedy = checkNotNull(solver.recommend(cells, cards, StrategyAlgorithm.GREEDY))
        val lookahead = checkNotNull(solver.recommend(cells, cards, StrategyAlgorithm.LIMITED_LOOKAHEAD))
        assertEquals(BoardCellState.CLOSED, state(cells, greedy))
        assertEquals(BoardCellState.CLOSED, state(cells, lookahead))
        assertNotEquals("", greedy.strategy)
        assertNotEquals("", lookahead.strategy)
    }

    private fun board(overrides: Map<GridCell, BoardCellState> = emptyMap()): List<BoardCellObservation> =
        (0 until 5).flatMap { row ->
            (0 until 9).map { column ->
                BoardCellObservation(row, column, overrides[GridCell(row, column)] ?: BoardCellState.CLOSED)
            }
        }

    private fun cards(configuration: InventoryConfiguration): List<ItemCardRecognition> =
        configuration.items.mapIndexed { index, item ->
            ItemCardRecognition(
                index = index,
                cardRegion = ScreenRegion(0, 0, 10, 10),
                spriteRegion = ScreenRegion(0, 0, 10, 10),
                footprintRegion = ScreenRegion(0, 0, 10, 10),
                countRegion = ScreenRegion(0, 0, 10, 10),
                shape = ItemShape(
                    rows = item.rows,
                    columns = item.columns,
                    occupiedCells = buildSet {
                        for (row in 0 until item.rows) for (column in 0 until item.columns) add(ShapeCell(row, column))
                    },
                ),
                remainingCount = item.count,
                isFinished = false,
                confidence = 1.0,
                spriteTemplate = RgbaPatch(1, 1, byteArrayOf(0, 0, 0, 0)),
            )
        }

    private fun state(cells: List<BoardCellObservation>, recommendation: CellRecommendation): BoardCellState =
        cells.first { it.row == recommendation.row && it.column == recommendation.column }.state

    private fun address(recommendation: CellRecommendation): String =
        "${'A' + recommendation.column}${recommendation.row + 1}"

    private fun address(cell: GridCell): String = "${'A' + cell.column}${cell.row + 1}"
}
