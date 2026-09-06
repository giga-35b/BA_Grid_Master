package com.bagridmaster.app.debug

import com.bagridmaster.app.analysis.itemCode
import com.bagridmaster.app.overlay.displayAddress
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.cellAddress
import com.bagridmaster.app.vision.BoardCellState

data class DebugAnnotation(
    val region: ScreenRegion, val label: String, val color: Int,
    val bottomLabel: Boolean = false, val aboveLabel: Boolean = false,
    val centerLabel: Boolean = false, val emphasis: Boolean = false,
)

/** Capture-pixel coordinates only: the preview must not apply overlay/system-bar offsets. */
fun AnalysisResult.debugAnnotations(): List<DebugAnnotation> = buildList {
    val board = geometry.board
    add(DebugAnnotation(board.region, "棋盘 ${board.columns}×${board.rows}", 0xFF50DDFF.toInt(), aboveLabel = true))
    boardCells.forEach { cell ->
        val region = board.cellRegion(cell.row, cell.column) ?: return@forEach
        val color = when (cell.state) {
            BoardCellState.CLOSED -> 0xFFBBBBBB.toInt()
            BoardCellState.SELECTED -> 0xFFFFFF40.toInt()
            BoardCellState.OPEN_EMPTY -> 0xFFFFFFFF.toInt()
            BoardCellState.OPEN_FRAGMENT -> 0xFFFFBB50.toInt()
            BoardCellState.OPEN_OBJECT -> 0xFF50DDFF.toInt()
            BoardCellState.UNCERTAIN -> 0xFFFF6688.toInt()
        }
        add(DebugAnnotation(region, "${cellAddress(cell.row, cell.column)} ${cell.state.debugSymbol()}", color))
    }
    inventory.forEach { item ->
        val shape = item.shape?.let { "${it.columns}×${it.rows}" } ?: "?"
        add(DebugAnnotation(item.cardRegion, "物品${itemCode(item.index)} $shape ×${item.remainingCount ?: "?"}", 0xFF55FFFF.toInt(), aboveLabel = true))
        add(DebugAnnotation(item.spriteRegion, "贴${itemCode(item.index)}", 0xFFFF77DD.toInt()))
        add(DebugAnnotation(item.footprintRegion, "形${itemCode(item.index)}", 0xFFFFFF66.toInt(), aboveLabel = true))
        add(DebugAnnotation(item.countRegion, "数${itemCode(item.index)}", 0xFF88FF88.toInt(), aboveLabel = true))
    }
    boardObjects.forEach { item ->
        item.observedCells.forEach cells@{ cell ->
            val region = board.cellRegion(cell.row, cell.column) ?: return@cells
            val matched = item.localMatch?.takeIf { it.accepted }?.candidates?.firstOrNull()
            val local = matched?.let {
                if (minOf(it.rows, it.columns) == 1) " ${maxOf(cell.row - it.origin.row, cell.column - it.origin.column) + 1}/${maxOf(it.rows, it.columns)}"
                else " (${cell.row - it.origin.row + 1},${cell.column - it.origin.column + 1})"
            }.orEmpty()
            add(DebugAnnotation(region, "${item.id} ${matched?.let { itemCode(it.itemIndex) } ?: item.possibleItemIndices.joinToString("/") { itemCode(it) }.ifEmpty { "?" }}$local",
                if (item.phase == BoardObjectPhase.COMPLETED) 0xFF50DDFF.toInt() else 0xFFFFBB50.toInt(), true))
        }
    }
    recommendations.forEach { cell ->
        val region = board.cellRegion(cell.row, cell.column) ?: return@forEach
        // A label anchor must never change the actual cell bounds.
        add(DebugAnnotation(region, "建议 ${cell.displayAddress()}", 0xFF38E8C6.toInt(), centerLabel = true, emphasis = true))
    }
}
