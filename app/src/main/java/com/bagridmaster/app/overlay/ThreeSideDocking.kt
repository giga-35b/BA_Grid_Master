package com.bagridmaster.app.overlay

import kotlin.math.abs

enum class DockSide {
    TOP,
    LEFT,
    RIGHT,
    NONE,
}

data class DockPosition(
    val x: Int,
    val y: Int,
    val side: DockSide,
)

object ThreeSideDocking {
    fun place(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        margin: Int,
        snapEnabled: Boolean,
        preferredSide: DockSide? = null,
    ): DockPosition {
        val minX = margin.coerceAtLeast(0)
        val minY = margin.coerceAtLeast(0)
        val maxX = (screenWidth - windowWidth - margin).coerceAtLeast(minX)
        val maxY = (screenHeight - windowHeight - margin).coerceAtLeast(minY)
        val clampedX = x.coerceIn(minX, maxX)
        val clampedY = y.coerceIn(minY, maxY)
        if (!snapEnabled) return DockPosition(clampedX, clampedY, DockSide.NONE)

        val side = preferredSide?.takeIf { it != DockSide.NONE } ?: listOf(
            DockSide.TOP to abs(clampedY - minY),
            DockSide.LEFT to abs(clampedX - minX),
            DockSide.RIGHT to abs(maxX - clampedX),
        ).minBy { it.second }.first

        return when (side) {
            DockSide.TOP -> DockPosition(clampedX, minY, side)
            DockSide.LEFT -> DockPosition(minX, clampedY, side)
            DockSide.RIGHT -> DockPosition(maxX, clampedY, side)
            DockSide.NONE -> DockPosition(clampedX, clampedY, side)
        }
    }
}
