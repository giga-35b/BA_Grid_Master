package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.BoardObjectObservation
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.RgbaPatch
import com.bagridmaster.app.analysis.cellAddress
import com.bagridmaster.app.analysis.itemCode
import kotlin.math.max

internal data class WholeBoardSilhouetteResult(
    val objects: List<BoardObjectObservation>,
    val cells: List<BoardCellObservation>,
    val diagnostics: List<String> = emptyList(),
)

/** Matches completed, colourless artwork over its entire candidate footprint. */
internal class WholeBoardSilhouetteMatcher {
    fun match(
        frame: RgbaFrame,
        board: BoardGeometry,
        cells: List<BoardCellObservation>,
        cards: List<ItemCardRecognition>,
    ): WholeBoardSilhouetteResult {
        val states = cells.associate { GridCell(it.row, it.column) to it.state }
        val rawGroups = connectedCellGroups(cells.filter { it.state == BoardCellState.OPEN_OBJECT }
            .mapTo(mutableSetOf()) { GridCell(it.row, it.column) })
        val boardMask = sampleCompletedMask(frame, board)
        val ordinaryCandidates = rawGroups.mapIndexed { groupIndex, group ->
            buildCandidates(groupIndex, group, board, states, cards, boardMask)
                .groupBy { it.itemIndex to it.cells }
                .mapNotNull { (_, variants) -> variants.maxByOrNull { it.score } }
                .sortedByDescending { it.score }
        }
        // A completed card can provide a trustworthy rectangular footprint even when no single
        // board cell reached the old 10% neutral-dark threshold. Scan the board once per Finish
        // card, using its preserved artwork when available and a stricter aggregate shape fallback
        // otherwise. This also tolerates edge cells that were labelled as coloured fragments.
        val finishedCandidates = buildFinishedCandidates(rawGroups.size, board, states, cards, boardMask)
        val candidatesByGroup = ordinaryCandidates + finishedCandidates
        if (candidatesByGroup.isEmpty()) return WholeBoardSilhouetteResult(emptyList(), cells)
        val diagnostics = candidatesByGroup.mapIndexed { index, ranked ->
            val group = if (index < rawGroups.size) rawGroups[index]
                .sortedWith(compareBy({ it.row }, { it.column }))
                .joinToString(" ") { cellAddress(it.row, it.column) }
            else "Finish 形状全盘扫描"
            val candidates = ranked.take(3).joinToString("；") {
                "物品${itemCode(it.itemIndex)} ${percent(it.score)} 召回${percent(it.templateRecall)} 全局剪影精度${percent(it.observedPrecision)} " +
                    it.cells.sortedWith(compareBy({ cell -> cell.row }, { cell -> cell.column }))
                        .joinToString(" ") { cell -> cellAddress(cell.row, cell.column) }
            }.ifEmpty { "无可用整体候选" }
            "原始剪影 $group；$candidates"
        }
        val accepted = candidatesByGroup.mapNotNull { ranked ->
            val best = ranked.firstOrNull() ?: return@mapNotNull null
            val runnerUp = ranked.drop(1).firstOrNull()
            val margin = best.score - (runnerUp?.score ?: 0.0)
            val decisiveExtension = runnerUp != null && best.cells.size > runnerUp.cells.size &&
                best.cells.containsAll(runnerUp.cells) && best.score >= STRONG_EXTENSION_SCORE &&
                (best.templateRecall - runnerUp.templateRecall >= MIN_EXTENSION_RECALL_GAIN ||
                    best.observedPrecision - runnerUp.observedPrecision >= MIN_EXTENSION_PRECISION_GAIN) &&
                margin >= MIN_EXTENSION_MARGIN
            best.copy(margin = margin).takeIf {
                if (it.shapeOnly) it.score >= MIN_SHAPE_ONLY_SCORE &&
                    it.templateRecall >= MIN_SHAPE_SUPPORT && margin >= MIN_SHAPE_MARGIN
                else it.score >= MIN_SCORE && it.templateRecall >= MIN_RECALL &&
                    (margin >= MIN_MARGIN || decisiveExtension)
            }
        }.sortedByDescending { it.score }

        // Jointly keep the strongest non-overlapping explanation. A weak earlier cell-size
        // hypothesis is not part of this conflict set and therefore cannot veto a better mask.
        val selected = mutableListOf<SilhouetteCandidate>()
        for (candidate in accepted) {
            if (selected.none { it.groupIndex == candidate.groupIndex || it.cells.any(candidate.cells::contains) }) {
                selected += candidate
            }
        }
        if (selected.isEmpty()) return WholeBoardSilhouetteResult(emptyList(), cells, diagnostics)

        val corrected = cells.map { cell ->
            val gridCell = GridCell(cell.row, cell.column)
            val candidate = selected.firstOrNull { gridCell in it.cells }
            if (candidate != null && (cell.state in setOf(BoardCellState.OPEN_EMPTY, BoardCellState.UNCERTAIN) ||
                    (candidate.finishedCard && cell.state != BoardCellState.SELECTED))) {
                cell.copy(state = BoardCellState.OPEN_OBJECT, presence = cell.presence?.copy(
                    explanation = if (candidate.finishedCard) "Finish 物品形状约束的整体剪影匹配，修正单格漏检"
                    else "棋盘整体剪影高置信匹配，修正单格漏检",
                ))
            } else cell
        }
        val objects = selected.sortedBy { it.cells.minOf { cell -> cell.row * board.columns + cell.column } }
            .mapIndexed { index, candidate ->
                val addresses = candidate.cells.sortedWith(compareBy({ it.row }, { it.column }))
                    .joinToString(" ") { cellAddress(it.row, it.column) }
                val correctedAddresses = candidate.cells.filter { states[it] != BoardCellState.OPEN_OBJECT }
                    .sortedWith(compareBy({ it.row }, { it.column }))
                    .joinToString(" ") { cellAddress(it.row, it.column) }
                val conflictText = if (accepted.any { it !== candidate && it.cells.any(candidate.cells::contains) }) {
                    "；联合选择中淘汰了覆盖冲突的较低分候选"
                } else ""
                BoardObjectObservation(
                    id = "C${index + 1}",
                    phase = BoardObjectPhase.COMPLETED,
                    observedCells = candidate.cells,
                    possibleItemIndices = listOf(candidate.itemIndex),
                    confidence = candidate.score,
                    evidence = "棋盘整体剪影匹配：物品${itemCode(candidate.itemIndex)}，占格 $addresses；" +
                        "整体分${percent(candidate.score)}，模板召回${percent(candidate.templateRecall)}，" +
                        "全局剪影精度${percent(candidate.observedPrecision)}，候选差距${percent(candidate.margin)}" +
                        (if (candidate.shapeOnly) "；Finish 形状约束聚合确认" else "") +
                        (if (correctedAddresses.isNotEmpty()) "；修正单格漏检 $correctedAddresses$conflictText" else conflictText),
                )
            }
        return WholeBoardSilhouetteResult(objects, corrected, diagnostics)
    }

    private fun buildCandidates(
        groupIndex: Int,
        group: Set<GridCell>,
        board: BoardGeometry,
        states: Map<GridCell, BoardCellState>,
        cards: List<ItemCardRecognition>,
        boardMask: BooleanArray,
    ): List<SilhouetteCandidate> = buildList {
        val componentMask = connectedSilhouetteMask(boardMask, group, board.columns, board.rows)
        for (card in cards) {
            if (card.isFinished) continue
            val shape = card.shape ?: continue
            val aligned = card.templatePreview?.aligned ?: continue
            if (shape.cellCount != shape.rows * shape.columns || shape.rows == shape.columns) continue
            val horizontalRows = minOf(shape.rows, shape.columns)
            val horizontalColumns = maxOf(shape.rows, shape.columns)
            val baseMask = alphaMask(aligned)
            if (baseMask.count { it } < 24) continue
            val masks = listOf(
                OrientedMask(aligned.width, aligned.height, baseMask),
                rotate(OrientedMask(aligned.width, aligned.height, baseMask), 2),
                rotate(OrientedMask(aligned.width, aligned.height, baseMask), 1),
                rotate(OrientedMask(aligned.width, aligned.height, baseMask), 3),
            )
            for ((rows, columns) in setOf(horizontalRows to horizontalColumns, horizontalColumns to horizontalRows)) {
                val oriented = masks.filter { mask ->
                    if (rows == horizontalRows) mask.width > mask.height else mask.height > mask.width
                }
                for (row in 0..board.rows - rows) for (column in 0..board.columns - columns) {
                    val footprint = rectangle(row, column, rows, columns)
                    if (!footprint.containsAll(group)) continue
                    if (footprint.any { states[it] !in ALLOWED_STATES }) continue
                    for (mask in oriented) {
                        val score = compare(mask, footprint, board.columns, board.rows, componentMask)
                        add(SilhouetteCandidate(groupIndex, card.index, footprint, score.combined,
                            score.templateRecall, score.observedPrecision))
                    }
                }
            }
        }
    }

    private fun buildFinishedCandidates(
        firstGroupIndex: Int,
        board: BoardGeometry,
        states: Map<GridCell, BoardCellState>,
        cards: List<ItemCardRecognition>,
        boardMask: BooleanArray,
    ): List<List<SilhouetteCandidate>> = cards.filter { it.isFinished }.mapIndexedNotNull { offset, card ->
        val shape = card.shape ?: return@mapIndexedNotNull null
        if (shape.cellCount != shape.rows * shape.columns || shape.rows == shape.columns) return@mapIndexedNotNull null
        val horizontalRows = minOf(shape.rows, shape.columns)
        val horizontalColumns = maxOf(shape.rows, shape.columns)
        val aligned = card.templatePreview?.aligned
        val masks = aligned?.let {
            val base = OrientedMask(it.width, it.height, alphaMask(it))
            listOf(base, rotate(base, 2), rotate(base, 1), rotate(base, 3))
        }.orEmpty()
        val ranked = mutableListOf<SilhouetteCandidate>()
        for ((rows, columns) in setOf(horizontalRows to horizontalColumns, horizontalColumns to horizontalRows)) {
            for (row in 0..board.rows - rows) for (column in 0..board.columns - columns) {
                val footprint = rectangle(row, column, rows, columns)
                if (footprint.any { states[it] == BoardCellState.SELECTED }) continue
                val seeds = footprint.count { states[it] in setOf(BoardCellState.OPEN_OBJECT, BoardCellState.OPEN_FRAGMENT) }
                val croppedMask = maskInside(boardMask, footprint, board.columns, board.rows)
                if (masks.isNotEmpty() && seeds >= 1) {
                    val oriented = masks.filter { mask ->
                        if (rows == horizontalRows) mask.width > mask.height else mask.height > mask.width
                    }
                    for (mask in oriented) {
                        val score = compare(mask, footprint, board.columns, board.rows, croppedMask)
                        ranked += SilhouetteCandidate(firstGroupIndex + offset, card.index, footprint,
                            score.combined, score.templateRecall, score.observedPrecision,
                            finishedCard = true)
                    }
                }
                if (seeds >= 2) {
                    shapeOnlyScore(footprint, boardMask, board.columns, board.rows)?.let { score ->
                        ranked += SilhouetteCandidate(firstGroupIndex + offset, card.index, footprint,
                            score.combined, score.templateRecall, score.observedPrecision,
                            finishedCard = true, shapeOnly = true)
                    }
                }
            }
        }
        ranked.groupBy { it.itemIndex to it.cells }
            .mapNotNull { (_, variants) -> variants.maxByOrNull { it.score } }
            .sortedByDescending { it.score }
            .takeIf { it.isNotEmpty() }
    }

    private fun maskInside(source: BooleanArray, footprint: Set<GridCell>, columns: Int, rows: Int): BooleanArray {
        val width = columns * SAMPLES_PER_CELL
        val height = rows * SAMPLES_PER_CELL
        return BooleanArray(source.size) { index ->
            val cell = GridCell(index / width / SAMPLES_PER_CELL, index % width / SAMPLES_PER_CELL)
            index < width * height && cell in footprint && source[index]
        }
    }

    private fun shapeOnlyScore(
        footprint: Set<GridCell>, source: BooleanArray, columns: Int, rows: Int,
    ): MaskScore? {
        val width = columns * SAMPLES_PER_CELL
        fun ratio(cell: GridCell): Double {
            var dark = 0
            var total = 0
            for (y in 2 until SAMPLES_PER_CELL - 2) for (x in 2 until SAMPLES_PER_CELL - 2) {
                val px = cell.column * SAMPLES_PER_CELL + x
                val py = cell.row * SAMPLES_PER_CELL + y
                if (py !in 0 until rows * SAMPLES_PER_CELL || px !in 0 until width) continue
                total++
                if (source[py * width + px]) dark++
            }
            return dark.toDouble() / total.coerceAtLeast(1)
        }
        val ratios = footprint.associateWith(::ratio)
        val support = ratios.filterValues { it >= SHAPE_CELL_DARK_RATIO }.keys
        if (support.size < maxOf(2, footprint.size / 3)) return null
        val minRow = footprint.minOf { it.row }; val maxRow = footprint.maxOf { it.row }
        val minColumn = footprint.minOf { it.column }; val maxColumn = footprint.maxOf { it.column }
        val rowCoverage = support.map { it.row }.distinct().size.toDouble() / (maxRow - minRow + 1)
        val columnCoverage = support.map { it.column }.distinct().size.toDouble() / (maxColumn - minColumn + 1)
        if (rowCoverage < 0.75 || columnCoverage < 0.75) return null
        val supportRatio = support.size.toDouble() / footprint.size
        val meanDark = ratios.values.average()
        val darkness = (meanDark / 0.13).coerceIn(0.0, 1.0)
        return MaskScore(darkness * 0.35 + supportRatio * 0.30 + rowCoverage * 0.175 + columnCoverage * 0.175,
            supportRatio, (darkness * 0.55 + (rowCoverage + columnCoverage) * 0.225).coerceIn(0.0, 1.0))
    }

    private fun compare(
        template: OrientedMask,
        footprint: Set<GridCell>,
        boardColumns: Int,
        boardRows: Int,
        componentMask: BooleanArray,
    ): MaskScore {
        val minRow = footprint.minOf { it.row }
        val maxRow = footprint.maxOf { it.row }
        val minColumn = footprint.minOf { it.column }
        val maxColumn = footprint.maxOf { it.column }
        val rows = maxRow - minRow + 1
        val columns = maxColumn - minColumn + 1
        var templateCount = 0
        var observedCount = 0
        var templateNearObserved = 0
        var observedNearTemplate = 0
        fun templateAt(x: Int, y: Int): Boolean {
            if (x !in 0 until columns * SAMPLES_PER_CELL || y !in 0 until rows * SAMPLES_PER_CELL) return false
            val tx = ((x + 0.5) * template.width / (columns * SAMPLES_PER_CELL)).toInt().coerceIn(0, template.width - 1)
            val ty = ((y + 0.5) * template.height / (rows * SAMPLES_PER_CELL)).toInt().coerceIn(0, template.height - 1)
            return template.mask[ty * template.width + tx]
        }
        fun observedAt(x: Int, y: Int): Boolean {
            if (x !in 0 until columns * SAMPLES_PER_CELL || y !in 0 until rows * SAMPLES_PER_CELL) return false
            val boardX = minColumn * SAMPLES_PER_CELL + x
            val boardY = minRow * SAMPLES_PER_CELL + y
            return componentMask[boardY * boardColumns * SAMPLES_PER_CELL + boardX]
        }
        for (y in 0 until rows * SAMPLES_PER_CELL) for (x in 0 until columns * SAMPLES_PER_CELL) {
            // Grid lines are not part of either object. Preserve almost all edge pixels so a
            // thin tail crossing into the next cell remains measurable.
            if (x % SAMPLES_PER_CELL == 0 || y % SAMPLES_PER_CELL == 0) continue
            val expected = templateAt(x, y)
            if (expected) {
                templateCount++
                if (near(x, y, ::observedAt)) templateNearObserved++
            }
        }
        val boardWidth = boardColumns * SAMPLES_PER_CELL
        val boardHeight = boardRows * SAMPLES_PER_CELL
        for (boardY in 0 until boardHeight) for (boardX in 0 until boardWidth) {
            if (!componentMask[boardY * boardWidth + boardX]) continue
            if (boardX % SAMPLES_PER_CELL == 0 || boardY % SAMPLES_PER_CELL == 0) continue
            observedCount++
            val localX = boardX - minColumn * SAMPLES_PER_CELL
            val localY = boardY - minRow * SAMPLES_PER_CELL
            if (near(localX, localY, ::templateAt)) observedNearTemplate++
        }
        val recall = templateNearObserved.toDouble() / templateCount.coerceAtLeast(1)
        val precision = observedNearTemplate.toDouble() / observedCount.coerceAtLeast(1)
        val harmonic = if (recall + precision == 0.0) 0.0 else 2 * recall * precision / (recall + precision)
        return MaskScore(harmonic * 0.45 + recall * 0.10 + precision * 0.45, recall, precision)
    }

    /** Follow the actual dark component beyond cells that passed the per-cell 10% threshold. */
    private fun connectedSilhouetteMask(
        source: BooleanArray,
        group: Set<GridCell>,
        boardColumns: Int,
        boardRows: Int,
    ): BooleanArray {
        val width = boardColumns * SAMPLES_PER_CELL
        val height = boardRows * SAMPLES_PER_CELL
        val cleaned = source.copyOf()
        for (y in 0 until height) for (x in 0 until width) {
            val nearGridLine = x % SAMPLES_PER_CELL <= 1 || x % SAMPLES_PER_CELL >= SAMPLES_PER_CELL - 2 ||
                y % SAMPLES_PER_CELL <= 1 || y % SAMPLES_PER_CELL >= SAMPLES_PER_CELL - 2
            if (nearGridLine) cleaned[y * width + x] = false
        }
        val expanded = BooleanArray(cleaned.size)
        for (index in cleaned.indices) if (cleaned[index]) {
            val x = index % width
            val y = index / width
            for (dy in -CONNECT_RADIUS..CONNECT_RADIUS) for (dx in -CONNECT_RADIUS..CONNECT_RADIUS) {
                if (x + dx in 0 until width && y + dy in 0 until height) expanded[(y + dy) * width + x + dx] = true
            }
        }
        val queue = IntArray(expanded.size)
        var head = 0
        var tail = 0
        for (index in cleaned.indices) {
            if (!cleaned[index]) continue
            val cell = GridCell(index / width / SAMPLES_PER_CELL, index % width / SAMPLES_PER_CELL)
            if (cell in group && expanded[index]) {
                expanded[index] = false
                queue[tail++] = index
            }
        }
        val reached = BooleanArray(cleaned.size)
        while (head < tail) {
            val index = queue[head++]
            reached[index] = true
            val x = index % width
            val y = index / width
            for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (expanded[next]) {
                    expanded[next] = false
                    queue[tail++] = next
                }
            }
        }
        return BooleanArray(cleaned.size) { cleaned[it] && reached[it] }
    }

    private fun near(x: Int, y: Int, predicate: (Int, Int) -> Boolean): Boolean {
        for (dy in -MATCH_RADIUS..MATCH_RADIUS) for (dx in -MATCH_RADIUS..MATCH_RADIUS) {
            if (predicate(x + dx, y + dy)) return true
        }
        return false
    }

    private fun sampleCompletedMask(frame: RgbaFrame, board: BoardGeometry): BooleanArray {
        val width = board.columns * SAMPLES_PER_CELL
        val height = board.rows * SAMPLES_PER_CELL
        return BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val px = (board.region.left + (x + 0.5) * board.region.width / width).toInt().coerceIn(0, frame.width - 1)
            val py = (board.region.top + (y + 0.5) * board.region.height / height).toInt().coerceIn(0, frame.height - 1)
            val offset = py * frame.rowStrideBytes + px * 4
            val r = frame.rgba8888[offset].toInt() and 255
            val g = frame.rgba8888[offset + 1].toInt() and 255
            val b = frame.rgba8888[offset + 2].toInt() and 255
            max(r, max(g, b)) < 165 && max(r, max(g, b)) - minOf(r, g, b) <= 60
        }
    }

    private fun alphaMask(patch: RgbaPatch) = BooleanArray(patch.width * patch.height) { index ->
        (patch.rgba8888[index * 4 + 3].toInt() and 255) >= 96
    }

    private fun rotate(source: OrientedMask, quarterTurns: Int): OrientedMask {
        var current = source
        repeat((quarterTurns % 4 + 4) % 4) {
            val rotated = BooleanArray(current.mask.size)
            val newWidth = current.height
            val newHeight = current.width
            for (y in 0 until current.height) for (x in 0 until current.width) {
                val nx = current.height - 1 - y
                val ny = x
                rotated[ny * newWidth + nx] = current.mask[y * current.width + x]
            }
            current = OrientedMask(newWidth, newHeight, rotated)
        }
        return current
    }

    private fun connectedCellGroups(source: Set<GridCell>): List<Set<GridCell>> {
        val remaining = source.toMutableSet()
        val groups = mutableListOf<Set<GridCell>>()
        while (remaining.isNotEmpty()) {
            val group = mutableSetOf<GridCell>()
            val queue = ArrayDeque<GridCell>()
            queue += remaining.first()
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                if (!remaining.remove(cell)) continue
                group += cell
                listOf(GridCell(cell.row - 1, cell.column), GridCell(cell.row + 1, cell.column),
                    GridCell(cell.row, cell.column - 1), GridCell(cell.row, cell.column + 1))
                    .filterTo(queue) { it in remaining }
            }
            groups += group
        }
        return groups
    }

    private fun rectangle(row: Int, column: Int, rows: Int, columns: Int): Set<GridCell> = buildSet {
        for (r in row until row + rows) for (c in column until column + columns) add(GridCell(r, c))
    }

    private fun percent(value: Double) = "${(value * 100).toInt()}%"

    private data class OrientedMask(val width: Int, val height: Int, val mask: BooleanArray)
    private data class MaskScore(val combined: Double, val templateRecall: Double, val observedPrecision: Double)
    private data class SilhouetteCandidate(
        val groupIndex: Int,
        val itemIndex: Int,
        val cells: Set<GridCell>,
        val score: Double,
        val templateRecall: Double,
        val observedPrecision: Double,
        val margin: Double = 0.0,
        val finishedCard: Boolean = false,
        val shapeOnly: Boolean = false,
    )

    companion object {
        private const val SAMPLES_PER_CELL = 24
        private const val MATCH_RADIUS = 2
        private const val CONNECT_RADIUS = 2
        private const val MIN_SCORE = 0.66
        private const val MIN_RECALL = 0.58
        private const val MIN_MARGIN = 0.055
        private const val STRONG_EXTENSION_SCORE = 0.85
        private const val MIN_EXTENSION_RECALL_GAIN = 0.020
        private const val MIN_EXTENSION_PRECISION_GAIN = 0.030
        private const val MIN_EXTENSION_MARGIN = 0.025
        private const val SHAPE_CELL_DARK_RATIO = 0.035
        private const val MIN_SHAPE_ONLY_SCORE = 0.78
        private const val MIN_SHAPE_SUPPORT = 0.50
        private const val MIN_SHAPE_MARGIN = 0.075
        private val ALLOWED_STATES = setOf(BoardCellState.OPEN_OBJECT, BoardCellState.OPEN_EMPTY, BoardCellState.UNCERTAIN)
    }
}
