package com.bagridmaster.app.analysis

/** Hypotheses used for this turn's planning, never written into the observed board/debug image. */
data class PlannedLitObject(
    val objectId: String,
    val itemIndex: Int,
    val footprints: List<Set<GridCell>>,
    val basis: String,
)
