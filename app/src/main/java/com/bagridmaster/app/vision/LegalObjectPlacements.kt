package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.ItemCardRecognition

internal data class ObjectPlacement(val itemIndex: Int, val origin: GridCell, val rows: Int, val columns: Int) {
    val cells: Set<GridCell> = (0 until rows).flatMap { r ->
        (0 until columns).map { c -> GridCell(origin.row + r, origin.column + c) }
    }.toSet()
}

internal fun legalObjectPlacements(
    board: BoardGeometry,
    states: Map<GridCell, BoardCellState>,
    observed: Set<GridCell>,
    cards: List<ItemCardRecognition>,
): List<ObjectPlacement> {
    if (observed.isEmpty()) return emptyList()
    return cards.flatMap { card ->
        val shape = card.shape ?: return@flatMap emptyList()
        if (card.isFinished || card.remainingCount == 0 || shape.rows <= 0 || shape.columns <= 0 ||
            shape.cellCount != shape.rows * shape.columns) return@flatMap emptyList()
        setOf(shape.rows to shape.columns, shape.columns to shape.rows).flatMap { (rows, columns) ->
            (0..board.rows - rows).flatMap { r ->
                (0..board.columns - columns).mapNotNull { c ->
                    val placement = ObjectPlacement(card.index, GridCell(r, c), rows, columns)
                    placement.takeIf { it.cells.containsAll(observed) && it.cells.all { cell ->
                        states[cell]?.isUnopened == true || states[cell] == BoardCellState.OPEN_FRAGMENT ||
                            states[cell] == BoardCellState.UNCERTAIN
                    } }
                }
            }
        }
    }
}
