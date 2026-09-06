package com.bagridmaster.app.overlay

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.BoardObjectObservation
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.vision.BoardCellState

data class KnownObjectAnnotation(val region: ScreenRegion, val label: String, val color: Int)

/** Presentation only: never turn a predicted footprint into an observed/fully opened object. */
fun knownObjectAnnotations(
    board: BoardGeometry,
    objects: List<BoardObjectObservation>,
    cells: List<BoardCellObservation>,
): List<KnownObjectAnnotation> = buildList {
    for (cell in cells.distinctBy { it.row to it.column }) {
        if (cell.state != BoardCellState.OPEN_EMPTY) continue
        val region = board.cellRegion(cell.row, cell.column) ?: continue
        add(KnownObjectAnnotation(region, "空", 0xFFA0A0A0.toInt()))
    }
    for (item in objects) {
        val observed = item.observedCells.filter {
            it.row in 0 until board.rows && it.column in 0 until board.columns
        }.toSet()
        if (observed.isEmpty()) continue
        val matchedType = item.localMatch?.takeIf { it.accepted }?.candidates?.firstOrNull()?.itemIndex
        val types = (matchedType?.let { listOf(it) } ?: item.possibleItemIndices)
            .distinct().sorted().filter { it in 0..2 }
        val type = if (types.isEmpty()) "未知" else types.joinToString("/") { com.bagridmaster.app.analysis.itemCode(it) }
        val completed = item.phase == BoardObjectPhase.COMPLETED
        val label = "$type ${if (completed) "全开" else "点亮"}"
        val color = if (completed) 0xFF50DDFF.toInt() else 0xFFFFBB50.toInt()
        val top = observed.minOf { it.row }
        val bottom = observed.maxOf { it.row }
        val left = observed.minOf { it.column }
        val right = observed.maxOf { it.column }
        val rectangular = (top..bottom).all { r -> (left..right).all { c -> GridCell(r, c) in observed } }
        if (completed && rectangular) {
            val start = checkNotNull(board.cellRegion(top, left))
            val end = checkNotNull(board.cellRegion(bottom, right))
            add(KnownObjectAnnotation(ScreenRegion(start.left, start.top, end.right, end.bottom), label, color))
        } else {
            // A malformed/merged non-rectangular CV component must not enclose unobserved cells.
            for ((index, cell) in observed.sortedWith(compareBy({ it.row }, { it.column })).withIndex()) {
                add(KnownObjectAnnotation(checkNotNull(board.cellRegion(cell.row, cell.column)),
                    if (!completed || index == 0) label else "", color))
            }
        }
    }
}
