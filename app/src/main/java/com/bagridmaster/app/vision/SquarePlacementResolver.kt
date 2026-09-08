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
     * Uses continuous background-subtracted evidence only after inventory recognition has reduced
     * the object to one known 2x2 or 3x3 shape. A near-zero opposite edge is strong evidence: a
     * modest continuation may determine the axis when it clearly outranks that clean edge.
     */
    fun resolveShapeAssisted(
        placements: List<ObjectPlacement>,
        edges: List<FragmentEdgeEvidence>,
    ): List<ObjectPlacement> {
        if (placements.isEmpty() || edges.isEmpty() ||
            placements.any { it.rows != it.columns || it.rows !in 2..3 } ||
            placements.map { it.itemIndex }.distinct().size != 1) return emptyList()
        val size = placements.first().rows
        val useful = edges.filter {
            assistedAxis(it.top, it.bottom, size) != null ||
                assistedAxis(it.left, it.right, size) != null
        }
        if (useful.none { assistedAxis(it.top, it.bottom, size) != null } ||
            useful.none { assistedAxis(it.left, it.right, size) != null }) return emptyList()
        return placements.filter { placement -> useful.all { edge ->
            val row = edge.cell.row - placement.origin.row
            val column = edge.cell.column - placement.origin.column
            accepts(assistedAxis(edge.top, edge.bottom, size), row, size) &&
                accepts(assistedAxis(edge.left, edge.right, size), column, size)
        } }
    }

    /** Uses explicit foreground coverage length along each edge for known square items. */
    fun resolveByOccupancy(
        placements: List<ObjectPlacement>,
        edges: List<FragmentEdgeEvidence>,
    ): List<ObjectPlacement> {
        if (placements.isEmpty() || edges.isEmpty() ||
            placements.any { it.rows != it.columns || it.rows !in 2..3 } ||
            placements.map { it.itemIndex }.distinct().size != 1) return emptyList()
        val size = placements.first().rows
        val useful = edges.filter {
            occupancyAxis(it.occupancy.top, it.occupancy.bottom, size) != null ||
                occupancyAxis(it.occupancy.left, it.occupancy.right, size) != null
        }
        if (useful.none { occupancyAxis(it.occupancy.top, it.occupancy.bottom, size) != null } ||
            useful.none { occupancyAxis(it.occupancy.left, it.occupancy.right, size) != null }) {
            return emptyList()
        }
        return placements.filter { placement -> useful.all { edge ->
            val row = edge.cell.row - placement.origin.row
            val column = edge.cell.column - placement.origin.column
            accepts(occupancyAxis(edge.occupancy.top, edge.occupancy.bottom, size), row, size) &&
                accepts(occupancyAxis(edge.occupancy.left, edge.occupancy.right, size), column, size)
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
        val axesReliable = edges.all { bestEffortAxesFit(best.first, it) }
        return if (axesReliable && best.second >= 0.56 && best.second - runnerUp >= 0.025) {
            listOf(best.first)
        } else emptyList()
    }

    private fun axis(negative: Double, positive: Double): Int? = when {
        negative >= 0.12 && positive >= 0.12 -> 0 // interior
        negative <= 0.04 && positive >= 0.12 -> -1 // first row/column
        positive <= 0.04 && negative >= 0.12 -> 1 // last row/column
        else -> null
    }

    private fun occupancyAxis(
        negative: BoundaryOccupancySide,
        positive: BoundaryOccupancySide,
        size: Int,
    ): Int? {
        val negativeScore = negative.score
        val positiveScore = positive.score
        val negativeDominates = occupancyDominates(negativeScore, positiveScore)
        val positiveDominates = occupancyDominates(positiveScore, negativeScore)
        return when {
            positiveDominates -> -1
            negativeDominates -> 1
            size >= 3 && negativeScore >= OCCUPANCY_INTERIOR_MIN &&
                positiveScore >= OCCUPANCY_INTERIOR_MIN -> 0
            else -> null
        }
    }

    private fun occupancyDominates(stronger: Double, weaker: Double): Boolean =
        (weaker <= OCCUPANCY_CLEAN_MAX && stronger >= OCCUPANCY_WEAK_CONTACT &&
            stronger - weaker >= OCCUPANCY_CLEAN_MARGIN) ||
            (stronger >= OCCUPANCY_STRONG_CONTACT &&
                stronger - weaker >= OCCUPANCY_AXIS_MARGIN &&
                stronger >= weaker * OCCUPANCY_AXIS_RATIO)

    private fun bestEffortAxesFit(
        placement: ObjectPlacement,
        edge: FragmentEdgeEvidence,
    ): Boolean {
        val row = edge.cell.row - placement.origin.row
        val column = edge.cell.column - placement.origin.column
        if (row !in 0..1 || column !in 0..1) return false
        val verticalAdvantage = if (row == 0) edge.bottom - edge.top else edge.top - edge.bottom
        val horizontalAdvantage = if (column == 0) edge.right - edge.left else edge.left - edge.right
        return verticalAdvantage >= BEST_EFFORT_AXIS_MARGIN &&
            horizontalAdvantage >= BEST_EFFORT_AXIS_MARGIN
    }

    private fun assistedAxis(negative: Double, positive: Double, size: Int): Int? {
        val negativeClean = negative <= CLEAN_OPPOSITE_MAX
        val positiveClean = positive <= CLEAN_OPPOSITE_MAX
        val negativeSupported = negative >= ASSISTED_CONTACT_MIN
        val positiveSupported = positive >= ASSISTED_CONTACT_MIN
        val negativeDominates = negative >= RELATIVE_CONTACT_MIN &&
            negative - positive >= RELATIVE_AXIS_MARGIN &&
            negative >= positive * RELATIVE_AXIS_RATIO
        val positiveDominates = positive >= RELATIVE_CONTACT_MIN &&
            positive - negative >= RELATIVE_AXIS_MARGIN &&
            positive >= negative * RELATIVE_AXIS_RATIO
        return when {
            negativeClean && positiveSupported && positive - negative >= ASSISTED_AXIS_MARGIN -> -1
            positiveClean && negativeSupported && negative - positive >= ASSISTED_AXIS_MARGIN -> 1
            positiveDominates -> -1
            negativeDominates -> 1
            size >= 3 && negative >= INTERIOR_CONTACT_MIN && positive >= INTERIOR_CONTACT_MIN -> 0
            else -> null
        }
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

    private const val OCCUPANCY_CLEAN_MAX = 0.10
    private const val OCCUPANCY_WEAK_CONTACT = 0.18
    private const val OCCUPANCY_CLEAN_MARGIN = 0.10
    private const val OCCUPANCY_STRONG_CONTACT = 0.28
    private const val OCCUPANCY_AXIS_MARGIN = 0.18
    private const val OCCUPANCY_AXIS_RATIO = 1.7
    private const val OCCUPANCY_INTERIOR_MIN = 0.22
    private const val BEST_EFFORT_AXIS_MARGIN = 0.12

    private const val CLEAN_OPPOSITE_MAX = 0.11
    private const val ASSISTED_CONTACT_MIN = 0.08
    private const val ASSISTED_AXIS_MARGIN = 0.06
    private const val RELATIVE_CONTACT_MIN = 0.30
    private const val RELATIVE_AXIS_MARGIN = 0.18
    private const val RELATIVE_AXIS_RATIO = 1.8
    private const val INTERIOR_CONTACT_MIN = 0.12
}
