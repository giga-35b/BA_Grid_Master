package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.GridCell

/** A square has no useful sprite rotation. Determine corner/edge/centre from the
 * four actual grid-edge contacts, then enumerate whole square footprints.
 * Missing contact is used only opposite a strong contact; no-contact axes stay unknown.
 */
internal object SquarePlacementResolver {
    fun resolve(placements: List<ObjectPlacement>, edges: List<FragmentEdgeEvidence>): List<ObjectPlacement> {
        if (placements.isEmpty() || placements.any { it.rows != it.columns } || edges.isEmpty()) return emptyList()
        val useful = edges.filter { axis(it.top, it.bottom) != null || axis(it.left, it.right) != null }
        if (useful.none { axis(it.top, it.bottom) != null } || useful.none { axis(it.left, it.right) != null }) return emptyList()
        return placements.filter { placement -> useful.all { edge ->
            val row = edge.cell.row - placement.origin.row
            val column = edge.cell.column - placement.origin.column
            accepts(axis(edge.top, edge.bottom), row, placement.rows) &&
                accepts(axis(edge.left, edge.right), column, placement.columns) &&
                // Positive evidence from other observed cells must also fit the same square.
                edges.all { e -> contactsFit(placement, e) }
        } }
    }

    private fun axis(negative: Double, positive: Double): Int? = when {
        negative >= 0.12 && positive >= 0.12 -> 0 // interior
        negative <= 0.04 && positive >= 0.12 -> -1 // first row/column
        positive <= 0.04 && negative >= 0.12 -> 1 // last row/column
        else -> null
    }

    private fun accepts(axis: Int?, offset: Int, size: Int): Boolean = when (axis) {
        -1 -> offset == 0
        1 -> offset == size - 1
        0 -> offset in 1 until size - 1
        else -> true
    }

    private fun contactsFit(placement: ObjectPlacement, edge: FragmentEdgeEvidence): Boolean =
        listOf(Triple(-1, 0, edge.top), Triple(0, 1, edge.right), Triple(1, 0, edge.bottom), Triple(0, -1, edge.left))
            .all { (dr, dc, strength) -> strength < 0.12 || GridCell(edge.cell.row + dr, edge.cell.column + dc) in placement.cells }
}
