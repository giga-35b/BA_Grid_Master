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

    /**
     * A 2x2 fragment can occupy only one corner, hence exactly two perpendicular neighbours.
     * When noisy contacts make the strict rules contradictory, rank complete footprints instead
     * of falling through to the generic four-direction neighbour expansion.
     */
    fun resolveBestEffortTwoByTwo(
        placements: List<ObjectPlacement>,
        edges: List<FragmentEdgeEvidence>,
    ): List<ObjectPlacement> {
        if (placements.isEmpty() || edges.isEmpty() ||
            placements.any { it.rows != 2 || it.columns != 2 }) return emptyList()
        val ranked = placements.distinctBy { it.itemIndex to it.cells }.map { placement ->
            val samples = edges.flatMap { edge -> listOf(
                GridCell(edge.cell.row - 1, edge.cell.column) to edge.top,
                GridCell(edge.cell.row, edge.cell.column + 1) to edge.right,
                GridCell(edge.cell.row + 1, edge.cell.column) to edge.bottom,
                GridCell(edge.cell.row, edge.cell.column - 1) to edge.left,
            ) }
            val score = samples.sumOf { (cell, strength) ->
                if (cell in placement.cells) strength else 1.0 - strength
            } / samples.size.coerceAtLeast(1)
            placement to score
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return emptyList()
        val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
        return if (best.second >= 0.56 && best.second - runnerUp >= 0.025) listOf(best.first)
            else emptyList()
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
