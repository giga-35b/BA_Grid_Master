package com.bagridmaster.app.analysis

import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.vision.BoardCellState
import kotlin.random.Random

data class RectInventoryItem(
    val rows: Int,
    val columns: Int,
    val count: Int,
) {
    val normalizedSize: Pair<Int, Int> get() = minOf(rows, columns) to maxOf(rows, columns)
}

enum class InventoryConfiguration(
    val label: String,
    val items: List<RectInventoryItem>,
) {
    ROUND_1(
        "1周目型",
        listOf(RectInventoryItem(1, 3, 4), RectInventoryItem(1, 4, 3), RectInventoryItem(2, 3, 1)),
    ),
    ROUND_2(
        "2周目型",
        listOf(RectInventoryItem(1, 2, 3), RectInventoryItem(2, 2, 4), RectInventoryItem(2, 4, 1)),
    ),
    ROUND_3(
        "3周目型",
        listOf(RectInventoryItem(1, 3, 3), RectInventoryItem(2, 2, 3), RectInventoryItem(3, 3, 1)),
    ),
    ROUND_4(
        "4周目型",
        listOf(RectInventoryItem(1, 3, 2), RectInventoryItem(2, 2, 3), RectInventoryItem(2, 3, 2)),
    ),
    ROUND_13_PLUS(
        "13周目以后型",
        listOf(RectInventoryItem(1, 3, 2), RectInventoryItem(1, 4, 2), RectInventoryItem(2, 4, 2)),
    );

    companion object {
        fun match(cards: List<ItemCardRecognition>): InventoryConfiguration? {
            if (cards.size != 3 || cards.any { it.shape == null }) return null
            val observed = cards.map { card ->
                val shape = checkNotNull(card.shape)
                Triple(minOf(shape.rows, shape.columns), maxOf(shape.rows, shape.columns), card.remainingCount)
            }.sortedWith(compareBy({ it.first }, { it.second }))
            return entries.firstOrNull { configuration ->
                val expected = configuration.items
                    .map { Triple(it.normalizedSize.first, it.normalizedSize.second, it.count) }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                observed.indices.all { index ->
                    observed[index].first == expected[index].first &&
                        observed[index].second == expected[index].second &&
                        (observed[index].third == null || observed[index].third!! <= expected[index].third)
                }
            }
        }
    }
}

data class GridCell(val row: Int, val column: Int)

/** Opening results are generated offline and kept here so the two cold-start states do no search. */
object PresetOpeningBook {
    data class Entry(
        val first: GridCell,
        val secondAfterEmpty: GridCell,
        val firstMissProbability: Double,
        val secondMissProbability: Double,
    )

    private val entries = mapOf(
        InventoryConfiguration.ROUND_1 to Entry(GridCell(2, 0), GridCell(2, 8), 0.2602089002, 0.2061590570),
        InventoryConfiguration.ROUND_2 to Entry(GridCell(1, 1), GridCell(1, 7), 0.1505828060, 0.0810819699),
        InventoryConfiguration.ROUND_3 to Entry(GridCell(2, 2), GridCell(2, 6), 0.2056961757, 0.1050826004),
        InventoryConfiguration.ROUND_4 to Entry(GridCell(1, 1), GridCell(3, 1), 0.1958154713, 0.1159509455),
        InventoryConfiguration.ROUND_13_PLUS to Entry(GridCell(1, 1), GridCell(3, 3), 0.2479511655, 0.1206209193),
    )

    operator fun get(configuration: InventoryConfiguration): Entry = checkNotNull(entries[configuration])

    fun get(configuration: InventoryConfiguration, algorithm: StrategyAlgorithm): Entry =
        if (configuration == InventoryConfiguration.ROUND_13_PLUS && algorithm == StrategyAlgorithm.GREEDY) {
            Entry(GridCell(2, 1), GridCell(2, 5), 0.2423064292, 0.1599713626)
        } else {
            get(configuration)
        }
}

class BoardStrategySolver {
    fun recommend(
        cells: List<BoardCellObservation>,
        cards: List<ItemCardRecognition>,
        algorithm: StrategyAlgorithm,
        excluded: Set<GridCell> = emptySet(),
        knownLitFootprints: List<List<Set<GridCell>>> = emptyList(),
    ): CellRecommendation? {
        if (cells.size != BOARD_ROWS * BOARD_COLUMNS) {
            return null
        }
        // A failed count/shape read is not evidence that the initial inventory still remains.
        // Finished cards may lose their footprint under the FINISH stamp, so they need no shape.
        if (cards.size != 3 || cards.any { card ->
                !card.isFinished && card.remainingCount != 0 &&
                    (card.shape == null || card.remainingCount == null || card.remainingCount < 0)
            }
        ) return null
        if (cells.any { it.state == BoardCellState.UNCERTAIN }) return null
        val configuration = InventoryConfiguration.match(cards)
        val items = cards.mapNotNull { card ->
            if (card.isFinished || card.remainingCount == 0) return@mapNotNull null
            val shape = card.shape ?: return@mapNotNull null
            val count = card.remainingCount ?: return@mapNotNull null
            RectInventoryItem(shape.rows, shape.columns, count)
        }
        if (items.sumOf { it.count } <= 0) return null
        val opening = configuration?.let { PresetOpeningBook.get(it, algorithm) }
        val opened = cells.filter { !it.state.isUnopened }
        val openedObjects = opened.filter { it.state == BoardCellState.OPEN_FRAGMENT || it.state == BoardCellState.OPEN_OBJECT }
        val openedEmpty = opened.filter { it.state == BoardCellState.OPEN_EMPTY }
        val openingInventoryMatches = configuration != null && cards.all { card ->
            val shape = card.shape ?: return@all false
            val expected = configuration.items.firstOrNull {
                it.normalizedSize == (minOf(shape.rows, shape.columns) to maxOf(shape.rows, shape.columns))
            }
            !card.isFinished && card.remainingCount == expected?.count
        }
        val preset = when {
            !openingInventoryMatches || opening == null -> null
            opened.isEmpty() -> opening.first
            openedObjects.isEmpty() && openedEmpty.size == 1 &&
                openedEmpty.single().row == opening.first.row &&
                openedEmpty.single().column == opening.first.column -> opening.secondAfterEmpty
            else -> null
        }
        if (preset != null && opening != null && preset !in excluded && stateAt(cells, preset)?.isUnopened == true) {
            return CellRecommendation(
                row = preset.row,
                column = preset.column,
                missProbability = if (opened.isEmpty()) opening.firstMissProbability else opening.secondMissProbability,
                strategy = "${configuration.label}预计算开局",
                isExploration = true,
            )
        }

        val ranked = rankComputed(cells, items, algorithm, excluded, knownLitFootprints = knownLitFootprints)
        return ranked.firstOrNull()
    }

    internal fun rankComputed(
        cells: List<BoardCellObservation>,
        items: List<RectInventoryItem>,
        algorithm: StrategyAlgorithm,
        excluded: Set<GridCell> = emptySet(),
        sampleBudget: Int = 2_400,
        knownLitFootprints: List<List<Set<GridCell>>> = emptyList(),
    ): List<CellRecommendation> {
        if (cells.any { it.state == BoardCellState.UNCERTAIN }) return emptyList()
        val empty = cells.filter { it.state == BoardCellState.OPEN_EMPTY }.mapTo(mutableSetOf()) { GridCell(it.row, it.column) }
        val blocked = cells.filter { it.state == BoardCellState.OPEN_OBJECT }
            .mapTo(mutableSetOf()) { GridCell(it.row, it.column) }
        val required = cells.filter { it.state == BoardCellState.OPEN_FRAGMENT }
            .mapTo(mutableSetOf()) { GridCell(it.row, it.column) }
        val candidates = cells.filter { it.state.isUnopened }
            .map { GridCell(it.row, it.column) }
            .filterNot { it in excluded }
        val posterior = legalLayoutSamples(items, empty, blocked, required, sampleBudget, knownLitFootprints)
        if (posterior.size < 64) return emptyList()
        val totalWeight = posterior.sumOf { it.weight }
        val current = DoubleArray(BOARD_ROWS * BOARD_COLUMNS)
        for (sample in posterior) for (cellIndex in current.indices) {
            if (sample.undiscovered and (1L shl cellIndex) != 0L) current[cellIndex] += sample.weight
        }
        for (cellIndex in current.indices) current[cellIndex] /= totalWeight
        if (candidates.none { current[index(it)] > 0.000001 }) return emptyList()
        return candidates.map { candidate ->
            val hit = current[index(candidate)]
            val score = if (algorithm == StrategyAlgorithm.GREEDY) {
                hit
            } else {
                val nextHitWeight = DoubleArray(current.size)
                val candidateBit = 1L shl index(candidate)
                for (sample in posterior) {
                    if (sample.undiscovered and candidateBit != 0L) continue
                    for (next in candidates) {
                        val nextIndex = index(next)
                        if (sample.undiscovered and (1L shl nextIndex) != 0L) nextHitWeight[nextIndex] += sample.weight
                    }
                }
                val bestMissThenHit = candidates.asSequence().filter { it != candidate }
                    .maxOfOrNull { nextHitWeight[index(it)] / totalWeight } ?: 0.0
                hit + LOOKAHEAD_DISCOUNT * bestMissThenHit
            }
            ScoredCell(candidate, hit, score)
        }.sortedWith(
            compareByDescending<ScoredCell> { kotlin.math.round(it.score * 1_000_000_000.0) }
                .thenBy { distanceFromCenter(it.cell) }
                .thenBy { it.cell.row }
                .thenBy { it.cell.column },
        ).map { scored ->
            CellRecommendation(
                row = scored.cell.row,
                column = scored.cell.column,
                missProbability = 1.0 - scored.hitProbability,
                strategy = if (algorithm == StrategyAlgorithm.GREEDY) "贪婪探索" else "有限前瞻探索",
                isExploration = true,
            )
        }
    }

    /**
     * Importance sampling of complete non-overlapping layouts. At each placement we sample
     * uniformly from the still-legal choices and multiply by that choice count, correcting the
     * sequential proposal bias. Every retained sample respects empty cells and observed fragments.
     */
    private fun legalLayoutSamples(
        items: List<RectInventoryItem>,
        empty: Set<GridCell>,
        blocked: Set<GridCell>,
        required: Set<GridCell>,
        sampleBudget: Int,
        knownLitFootprints: List<List<Set<GridCell>>>,
    ): List<LayoutSample> {
        val forbiddenMask = (empty + blocked).fold(0L) { mask, cell -> mask or (1L shl index(cell)) }
        val requiredMask = required.fold(0L) { mask, cell -> mask or (1L shl index(cell)) }
        val objects = items.filter { it.count > 0 }
            .flatMap { item -> List(item.count) { item } }
            .sortedByDescending { it.rows * it.columns }
        if (objects.isEmpty()) return emptyList()
        // These lit objects have already been deducted from `items`. Place each exactly once,
        // but do not count it as an undiscovered target. Never block the union of alternatives.
        val knownOptions = knownLitFootprints.map { alternatives ->
            alternatives.map { footprint -> footprint.fold(0L) { mask, cell -> mask or (1L shl index(cell)) } }
                .distinct().filter { it and forbiddenMask == 0L }.toLongArray()
        }
        val options = knownOptions + objects.map { item ->
            placements(item).map { placement ->
                placement.fold(0L) { mask, cell -> mask or (1L shl index(cell)) }
            }.filter { it and forbiddenMask == 0L }.toLongArray()
        }
        if (options.any { it.isEmpty() }) return emptyList()
        val seed = (forbiddenMask xor (requiredMask shl 1) xor items.hashCode().toLong() xor
            (if (knownLitFootprints.isEmpty()) 0L else knownLitFootprints.hashCode().toLong())).toInt()
        val random = Random(seed)
        val symmetries = (0..3).filter { reflection ->
            reflect(forbiddenMask, reflection) == forbiddenMask && reflect(requiredMask, reflection) == requiredMask &&
                knownOptions.all { choices -> choices.map { reflect(it, reflection) }.toSet() == choices.toSet() }
        }
        val result = ArrayList<LayoutSample>(sampleBudget * symmetries.size)
        var acceptedSamples = 0
        repeat(sampleBudget * 12) {
            if (acceptedSamples >= sampleBudget) return result
            var occupied = 0L
            var undiscovered = 0L
            var weight = 1.0
            for ((objectIndex, objectOptions) in options.withIndex()) {
                var choices = 0
                var selected = 0L
                for (option in objectOptions) {
                    if (option and occupied != 0L) continue
                    choices++
                    if (random.nextInt(choices) == 0) selected = option
                }
                if (choices == 0) return@repeat
                occupied = occupied or selected
                if (objectIndex >= knownOptions.size && selected and requiredMask == 0L) undiscovered = undiscovered or selected
                weight *= choices
            }
            if (occupied and requiredMask == requiredMask) {
                acceptedSamples++
                for (reflection in symmetries) {
                    result += LayoutSample(reflect(occupied, reflection), reflect(undiscovered, reflection), weight)
                }
            }
        }
        return result
    }

    private fun placements(item: RectInventoryItem): List<Set<GridCell>> {
        val dimensions = buildSet {
            add(item.rows to item.columns)
            add(item.columns to item.rows)
        }
        return dimensions.flatMap { (height, width) ->
            (0..BOARD_ROWS - height).flatMap { row ->
                (0..BOARD_COLUMNS - width).map { column ->
                    buildSet {
                        for (r in row until row + height) for (c in column until column + width) {
                            add(GridCell(r, c))
                        }
                    }
                }
            }
        }
    }

    private fun reflect(mask: Long, reflection: Int): Long {
        if (reflection == 0) return mask
        var result = 0L
        for (row in 0 until BOARD_ROWS) for (column in 0 until BOARD_COLUMNS) {
            if (mask and (1L shl (row * BOARD_COLUMNS + column)) == 0L) continue
            val reflectedRow = if (reflection and 1 != 0) BOARD_ROWS - 1 - row else row
            val reflectedColumn = if (reflection and 2 != 0) BOARD_COLUMNS - 1 - column else column
            result = result or (1L shl (reflectedRow * BOARD_COLUMNS + reflectedColumn))
        }
        return result
    }

    private fun stateAt(cells: List<BoardCellObservation>, cell: GridCell): BoardCellState? =
        cells.firstOrNull { it.row == cell.row && it.column == cell.column }?.state

    private fun index(cell: GridCell): Int = cell.row * BOARD_COLUMNS + cell.column

    private fun distanceFromCenter(cell: GridCell): Int =
        kotlin.math.abs(cell.row * 2 - (BOARD_ROWS - 1)) + kotlin.math.abs(cell.column * 2 - (BOARD_COLUMNS - 1))

    private data class ScoredCell(
        val cell: GridCell,
        val hitProbability: Double,
        val score: Double,
    )

    private data class LayoutSample(val occupied: Long, val undiscovered: Long, val weight: Double)

    companion object {
        private const val BOARD_ROWS = 5
        private const val BOARD_COLUMNS = 9
        private const val LOOKAHEAD_DISCOUNT = 0.78
    }
}
