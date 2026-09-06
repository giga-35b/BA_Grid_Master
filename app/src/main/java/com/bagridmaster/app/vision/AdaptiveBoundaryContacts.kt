package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Theme-independent continuation evidence.  A human recognises a fragment at an edge mainly
 * because colour/brightness structure varies *along* that edge and the structure remains visible
 * in the last few lines before the grid border.  No hue is assigned to either foreground or
 * background here.
 */
internal object AdaptiveBoundaryContacts {
    fun measure(frame: RgbaFrame, region: ScreenRegion): DoubleArray {
        val safe = ScreenRegion(region.left.coerceIn(0, frame.width - 1),
            region.top.coerceIn(0, frame.height - 1), region.right.coerceIn(1, frame.width),
            region.bottom.coerceIn(1, frame.height))
        if (safe.width < 12 || safe.height < 12) return DoubleArray(4)
        return doubleArrayOf(
            side(frame, safe, 0), side(frame, safe, 1), side(frame, safe, 2), side(frame, safe, 3),
        )
    }

    private fun side(frame: RgbaFrame, region: ScreenRegion, side: Int): Double {
        val perpendicular = if (side % 2 == 0) region.height else region.width
        val tangent = if (side % 2 == 0) region.width else region.height
        val borderInset = max(2, (perpendicular * 0.025f).roundToInt())
        val bandDepth = max(borderInset + 2, (perpendicular * 0.17f).roundToInt())
        val tangentInset = max(3, (tangent * 0.12f).roundToInt())
        val depthStep = max(1, (perpendicular * 0.025f).roundToInt())
        val scores = mutableListOf<Double>()
        var depth = borderInset
        while (depth <= bandDepth) {
            val colours = mutableListOf<IntArray>()
            var position = tangentInset
            while (position < tangent - tangentInset) {
                val x = when (side) {
                    1 -> region.right - 1 - depth
                    3 -> region.left + depth
                    else -> region.left + position
                }
                val y = when (side) {
                    0 -> region.top + depth
                    2 -> region.bottom - 1 - depth
                    else -> region.top + position
                }
                colours += rgb(frame, x, y)
                position += 2
            }
            if (colours.size >= 5) scores += lineTexture(colours)
            depth += depthStep
        }
        if (scores.isEmpty()) return 0.0
        // The closest three usable lines implement "texture persists until the border".  A wider
        // line can support that evidence, but cannot rescue a clean near-border background strip.
        val near = scores.take(3).average()
        val inner = scores.average()
        val persistent = minOf(near, inner * 1.35)
        // A broad sprite side or decorative background can create moderate variation without
        // actually reaching the border (the failure mode of the old 22% band). Only persistent,
        // conspicuous structure becomes positive continuation evidence; weaker values remain an
        // unknown/clean edge rather than being promoted by the planner's much lower 12% gate.
        return persistent.takeIf { it >= 0.40 }?.coerceIn(0.0, 1.0) ?: 0.0
    }

    private fun lineTexture(colours: List<IntArray>): Double {
        val mean = DoubleArray(3) { channel -> colours.sumOf { it[channel].toDouble() } / colours.size }
        val spread = sqrt(colours.sumOf { colour ->
            val dr = colour[0] - mean[0]; val dg = colour[1] - mean[1]; val db = colour[2] - mean[2]
            (dr * dr + dg * dg + db * db) / 3.0
        } / colours.size) / 72.0
        var activeChanges = 0
        for (index in 1 until colours.size) {
            val previous = colours[index - 1]; val current = colours[index]
            val distance = max(abs(current[0] - previous[0]),
                max(abs(current[1] - previous[1]), abs(current[2] - previous[2])))
            if (distance >= 20) activeChanges++
        }
        val changeRatio = activeChanges.toDouble() / (colours.size - 1).coerceAtLeast(1)
        // Spread detects broad solid artwork against the local background; changes detect lines
        // and detail.  Both are colour-agnostic, and a smooth single-colour cover scores near zero.
        return max(spread, changeRatio * 2.4).coerceIn(0.0, 1.0)
    }

    private fun rgb(frame: RgbaFrame, x: Int, y: Int): IntArray {
        val safeX = x.coerceIn(0, frame.width - 1)
        val safeY = y.coerceIn(0, frame.height - 1)
        val offset = safeY * frame.rowStrideBytes + safeX * 4
        return intArrayOf(frame.rgba8888[offset].toInt() and 255,
            frame.rgba8888[offset + 1].toInt() and 255, frame.rgba8888[offset + 2].toInt() and 255)
    }
}
