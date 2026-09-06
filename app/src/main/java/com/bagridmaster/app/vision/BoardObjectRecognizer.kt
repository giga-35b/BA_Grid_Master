package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.BoardObjectObservation
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.ItemTypeScore

/** Rotation-independent diagnostics. Pixel components separate touching footprint rectangles.
 * Ambiguous components retain candidate types; these estimates never constrain the solver.
 */
class BoardObjectRecognizer {
    fun recognize(
        frame: RgbaFrame,
        board: BoardGeometry,
        cells: List<BoardCellObservation>,
        cards: List<ItemCardRecognition>,
    ): List<BoardObjectObservation> = recognizeWithCorrections(frame, board, cells, cards).objects

    internal fun recognizeWithCorrections(
        frame: RgbaFrame,
        board: BoardGeometry,
        cells: List<BoardCellObservation>,
        cards: List<ItemCardRecognition>,
    ): WholeBoardSilhouetteResult {
        val wholeBoard = WholeBoardSilhouetteMatcher().match(frame, board, cells, cards)
        val correctedCells = wholeBoard.cells
        val states = correctedCells.associate { GridCell(it.row, it.column) to it.state }
        val width = board.columns * SAMPLES_PER_CELL
        val height = board.rows * SAMPLES_PER_CELL
        val result = wholeBoard.objects.toMutableList()
        for (phase in BoardObjectPhase.entries) {
            val target = if (phase == BoardObjectPhase.COMPLETED) BoardCellState.OPEN_OBJECT else BoardCellState.OPEN_FRAGMENT
            val mask = BooleanArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val cell = GridCell(y / SAMPLES_PER_CELL, x / SAMPLES_PER_CELL)
                if (states[cell] != target) continue
                val px = (board.region.left + (x + 0.5) * board.region.width / width).toInt().coerceIn(0, frame.width - 1)
                val py = (board.region.top + (y + 0.5) * board.region.height / height).toInt().coerceIn(0, frame.height - 1)
                val offset = py * frame.rowStrideBytes + px * 4
                val r = frame.rgba8888[offset].toInt() and 255
                val g = frame.rgba8888[offset + 1].toInt() and 255
                val b = frame.rgba8888[offset + 2].toInt() and 255
                mask[y * width + x] = if (phase == BoardObjectPhase.COMPLETED) {
                    maxOf(r, g, b) < 165 && maxOf(r, g, b) - minOf(r, g, b) <= 60
                } else hueBin(r, g, b) != null
            }
            val groups = pixelGroups(mask, width, height)
            // Each physical cell can hold only one object. Rejoin disconnected artwork in it.
            val joined = mutableListOf<MutableSet<GridCell>>()
            for (group in groups) {
                val overlaps = joined.filter { prior -> prior.any { it in group } }
                val merged = group.toMutableSet()
                overlaps.forEach { merged.addAll(it) }
                joined.removeAll(overlaps.toSet())
                joined += merged
            }
            val unassigned = correctedCells.filter { it.state == target }
                .map { GridCell(it.row, it.column) }.filter { cell -> joined.none { cell in it } }
            // Do not hide cell evidence just because pixel grouping was too weak.
            joined.addAll(unassigned.map { mutableSetOf(it) })
            for (group in joined.sortedBy { it.minOf { cell -> cell.row * board.columns + cell.column } }) {
                if (phase == BoardObjectPhase.COMPLETED && wholeBoard.objects.any { objectMatch ->
                        group.any { it in objectMatch.observedCells }
                    }) continue
                val minRow = group.minOf { it.row }
                val maxRow = group.maxOf { it.row }
                val minColumn = group.minOf { it.column }
                val maxColumn = group.maxOf { it.column }
                val fullRectangle = group.size == (maxRow - minRow + 1) * (maxColumn - minColumn + 1)
                val shapeCandidates = cards.filter { card ->
                    val shape = card.shape ?: return@filter false
                    if (phase == BoardObjectPhase.LIT && (card.isFinished || card.remainingCount == 0)) return@filter false
                    val orientations = setOf(shape.rows to shape.columns, shape.columns to shape.rows)
                    if (phase == BoardObjectPhase.COMPLETED) {
                        fullRectangle && (maxRow - minRow + 1 to maxColumn - minColumn + 1) in orientations
                    } else {
                        orientations.any { (rows, columns) ->
                            (0..board.rows - rows).any { row ->
                                (0..board.columns - columns).any { column ->
                                    group.all { it.row in row until row + rows && it.column in column until column + columns } &&
                                        (row until row + rows).all { r ->
                                            (column until column + columns).all { c ->
                                                val state = states[GridCell(r, c)]
                                                state?.isUnopened == true || state == BoardCellState.OPEN_FRAGMENT || state == BoardCellState.UNCERTAIN
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
                val histogram = if (phase == BoardObjectPhase.LIT) observedHistogram(frame, board, group) else DoubleArray(FragmentPixels.APPEARANCE_BINS)
                val scored = shapeCandidates.map { card ->
                    val reference = DoubleArray(FragmentPixels.APPEARANCE_BINS)
                    val bytes = card.spriteTemplate.rgba8888
                    val artwork = FragmentPixels.templateMask(card.spriteTemplate)
                    for (offset in 0 until bytes.size - 3 step 4) {
                        if (!artwork[offset / 4]) continue
                        val bin = hueBin(bytes[offset].toInt() and 255, bytes[offset + 1].toInt() and 255, bytes[offset + 2].toInt() and 255)
                        if (bin != null) reference[bin]++
                    }
                    normalize(reference)
                    card.index to histogram.indices.sumOf { minOf(histogram[it], reference[it]) }
                }.sortedByDescending { it.second }
                val best = scored.firstOrNull()?.second ?: 0.0
                val candidates = if (phase == BoardObjectPhase.LIT && best >= 0.35) {
                    scored.filter { it.second >= best - 0.15 }.map { it.first }
                } else shapeCandidates.map { it.index }
                val evidence = when {
                    candidates.isEmpty() -> "轮廓与清单无法唯一对应；可能存在连片、漏格或形状缺失"
                    phase == BoardObjectPhase.COMPLETED -> "剪影连通区域与完整矩形尺寸匹配；相邻物品可能合并"
                    best >= 0.35 -> "可行尺寸 + 无旋转假设的色相分布匹配；仅已见格确定"
                    else -> "仅按可行尺寸列出候选；颜色证据不足，完整范围未知"
                }
                result += BoardObjectObservation(
                    id = (if (phase == BoardObjectPhase.COMPLETED) "C" else "L") + (result.count { it.phase == phase } + 1),
                    phase = phase,
                    observedCells = group,
                    possibleItemIndices = candidates,
                    confidence = if (phase == BoardObjectPhase.LIT) best else if (candidates.size == 1) 0.75 else 0.0,
                    evidence = evidence,
                    typeScores = if (phase == BoardObjectPhase.LIT) scored.map { (index, score) ->
                        ItemTypeScore(index, score, index in candidates)
                    } else emptyList(),
                )
            }
        }
        return WholeBoardSilhouetteResult(result, correctedCells, wholeBoard.diagnostics)
    }

    private fun pixelGroups(mask: BooleanArray, width: Int, height: Int): List<Set<GridCell>> {
        val expanded = BooleanArray(mask.size)
        for (i in mask.indices) if (mask[i]) {
            val x = i % width
            val y = i / width
            for (dy in -1..1) for (dx in -1..1) {
                if (x + dx in 0 until width && y + dy in 0 until height) expanded[(y + dy) * width + x + dx] = true
            }
        }
        val groups = mutableListOf<Set<GridCell>>()
        val queue = IntArray(mask.size)
        for (start in expanded.indices) {
            if (!expanded[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            expanded[start] = false
            val counts = mutableMapOf<GridCell, Int>()
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                if (mask[i]) {
                    val cell = GridCell(y / SAMPLES_PER_CELL, x / SAMPLES_PER_CELL)
                    counts[cell] = (counts[cell] ?: 0) + 1
                }
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    if (x + dx !in 0 until width || y + dy !in 0 until height) continue
                    val next = (y + dy) * width + x + dx
                    if (expanded[next]) { expanded[next] = false; queue[tail++] = next }
                }
            }
            val group = counts.filterValues { it >= 6 }.keys
            if (group.isNotEmpty()) groups += group
        }
        return groups
    }

    private fun observedHistogram(frame: RgbaFrame, board: BoardGeometry, cells: Set<GridCell>): DoubleArray {
        val histogram = DoubleArray(FragmentPixels.APPEARANCE_BINS)
        for (cell in cells) {
            val region = board.cellRegion(cell.row, cell.column) ?: continue
            for (y in region.top.coerceAtLeast(0) until region.bottom.coerceAtMost(frame.height) step 3) {
                for (x in region.left.coerceAtLeast(0) until region.right.coerceAtMost(frame.width) step 3) {
                    val offset = y * frame.rowStrideBytes + x * 4
                    val bin = hueBin(frame.rgba8888[offset].toInt() and 255, frame.rgba8888[offset + 1].toInt() and 255, frame.rgba8888[offset + 2].toInt() and 255)
                    if (bin != null) histogram[bin]++
                }
            }
        }
        normalize(histogram)
        return histogram
    }

    private fun normalize(histogram: DoubleArray) {
        val sum = histogram.sum()
        if (sum > 0) for (i in histogram.indices) histogram[i] /= sum
    }

    private fun hueBin(r: Int, g: Int, b: Int): Int? = FragmentPixels.appearanceBin(r, g, b)

    companion object { private const val SAMPLES_PER_CELL = 24 }
}
