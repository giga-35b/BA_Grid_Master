package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** A colour model learned only from the non-object pixels shared by strong fragment cells. */
internal class OpenCellBackgroundModel private constructor(
    private val colours: List<IntArray>,
    val confidence: Double,
) {
    data class Measurement(
        val backgroundRatio: Double,
        val residualConnectedRatio: Double,
        val residualChangeRatio: Double,
    )

    /** True when a pixel belongs to the opened-cell background palette learned for this board. */
    fun matches(colour: IntArray, tolerance: Int = MATCH_DISTANCE): Boolean =
        colours.any { reference -> colourDistance(colour, reference) <= tolerance }

    fun measure(frame: RgbaFrame, region: ScreenRegion): Measurement {
        val sampled = sample(frame, region)
        if (sampled.width <= 0 || sampled.colours.isEmpty()) return Measurement(0.0, 1.0, 1.0)
        val residual = BooleanArray(sampled.colours.size)
        var background = 0
        var changes = 0
        var comparisons = 0
        sampled.colours.forEachIndexed { index, colour ->
            val matches = matches(colour)
            if (matches) background++ else residual[index] = true
            val x = index % sampled.width
            val y = index / sampled.width
            if (x > 0) {
                comparisons++
                if (colourDistance(colour, sampled.colours[index - 1]) >= CHANGE_DISTANCE) changes++
            }
            if (y > 0) {
                comparisons++
                if (colourDistance(colour, sampled.colours[index - sampled.width]) >= CHANGE_DISTANCE) changes++
            }
        }
        val count = sampled.colours.size.coerceAtLeast(1).toDouble()
        return Measurement(
            backgroundRatio = background / count,
            residualConnectedRatio = FragmentPixels.largestComponent(residual, sampled.width) / count,
            residualChangeRatio = changes.toDouble() / comparisons.coerceAtLeast(1),
        )
    }

    companion object {
        /**
         * Conservative one-cell fallback used only for boundary occupancy. The background group
         * must dominate the cell and recur in every quadrant; compact sprite colours are excluded.
         */
        fun learnLocal(frame: RgbaFrame, region: ScreenRegion): OpenCellBackgroundModel? {
            val sampled = sample(frame, region)
            if (sampled.colours.isEmpty()) return null
            val counts = sampled.colours.groupingBy(::bin).eachCount()
            val quadrants = mutableMapOf<Int, MutableSet<Int>>()
            sampled.colours.forEachIndexed { index, colour ->
                val x = index % sampled.width
                val y = index / sampled.width
                val quadrant = (if (x >= sampled.width / 2) 1 else 0) +
                    (if (y >= sampled.height / 2) 2 else 0)
                quadrants.getOrPut(bin(colour)) { mutableSetOf() } += quadrant
            }
            val anchor = counts.entries
                .filter { (code, count) ->
                    count >= sampled.colours.size * LOCAL_ANCHOR_SUPPORT &&
                        (quadrants[code]?.size ?: 0) == 4
                }
                .maxByOrNull { it.value }?.key ?: return null
            val paletteBins = counts.entries
                .filter { (code, count) ->
                    count >= max(2, sampled.colours.size / 100) &&
                        (quadrants[code]?.size ?: 0) >= 3 &&
                        binDistance(code, anchor) <= LOCAL_BIN_RADIUS
                }
                .sortedByDescending { it.value }
                .take(LOCAL_MAX_COLOURS)
                .map { it.key }
            val support = counts.filterKeys { it in paletteBins }.values.sum().toDouble() /
                sampled.colours.size.coerceAtLeast(1)
            if (paletteBins.isEmpty() || support < LOCAL_GROUP_SUPPORT) return null
            val representatives = paletteBins.mapNotNull { code ->
                val matching = sampled.colours.filter { bin(it) == code }
                if (matching.isEmpty()) null else IntArray(3) { channel ->
                    matching.map { it[channel] }.sorted()[matching.size / 2]
                }
            }
            if (representatives.isEmpty()) return null
            return OpenCellBackgroundModel(representatives,
                (0.55 + support * 0.32).coerceIn(0.60, 0.78))
        }

        fun learn(frame: RgbaFrame, regions: List<ScreenRegion>): OpenCellBackgroundModel? {
            if (regions.size < 2) return null
            val samples = regions.map { sample(frame, it) }.filter { it.colours.isNotEmpty() }
            if (samples.size < 2) return null
            val cellBins = samples.map { sampled ->
                val counts = sampled.colours.groupingBy(::bin).eachCount()
                val quadrants = mutableMapOf<Int, MutableSet<Int>>()
                sampled.colours.forEachIndexed { index, colour ->
                    val x = index % sampled.width
                    val y = index / sampled.width
                    val quadrant = (if (x >= sampled.width / 2) 1 else 0) +
                        (if (y >= sampled.height / 2) 2 else 0)
                    quadrants.getOrPut(bin(colour)) { mutableSetOf() } += quadrant
                }
                counts.filter { (code, count) ->
                    count >= max(3, sampled.colours.size / 40) &&
                        ((quadrants[code]?.size ?: 0) >= 2 || count >= sampled.colours.size / 8)
                }.keys
            }
            val requiredCells = max(2, ceil(samples.size * 0.60).toInt())
            val commonBins = cellBins.flatten().groupingBy { it }.eachCount()
                .filterValues { it >= requiredCells }.keys

            // A fragment cell can contain local scenery colours absent from other opened cells.
            // Treat only broad colours spanning at least three quadrants as local background;
            // compact object artwork normally stays in one or two quadrants.
            val regionalBins = samples.flatMap { sampled ->
                val counts = sampled.colours.groupingBy(::bin).eachCount()
                val quadrants = mutableMapOf<Int, MutableSet<Int>>()
                sampled.colours.forEachIndexed { index, colour ->
                    val x = index % sampled.width
                    val y = index / sampled.width
                    val quadrant = (if (x >= sampled.width / 2) 1 else 0) +
                        (if (y >= sampled.height / 2) 2 else 0)
                    quadrants.getOrPut(bin(colour)) { mutableSetOf() } += quadrant
                }
                counts.filter { (code, count) ->
                    count >= sampled.colours.size / 10 && (quadrants[code]?.size ?: 0) >= 3
                }.keys
            }.toSet()
            val modelBins = commonBins + regionalBins
            if (modelBins.isEmpty()) return null
            val representatives = modelBins.mapNotNull { code ->
                val matching = samples.flatMap { it.colours.asIterable() }.filter { bin(it) == code }
                if (matching.isEmpty()) null else IntArray(3) { channel ->
                    matching.map { it[channel] }.sorted()[matching.size / 2]
                }
            }.take(MAX_COLOURS)
            if (representatives.isEmpty()) return null
            val agreement = cellBins.count { bins -> commonBins.any { it in bins } }.toDouble() / samples.size
            val regionalFallback = if (commonBins.isEmpty() && regionalBins.isNotEmpty()) 0.65 else 0.0
            return OpenCellBackgroundModel(representatives,
                maxOf(regionalFallback,
                    0.45 + agreement * 0.45 + minOf(0.10, representatives.size * 0.01))
                    .coerceIn(0.0, 1.0))
        }

        private data class SampledCell(
            val width: Int,
            val height: Int,
            val colours: List<IntArray>,
        )

        private fun sample(frame: RgbaFrame, region: ScreenRegion): SampledCell {
            val insetX = max(3, (region.width * 0.10f).roundToInt())
            val insetY = max(3, (region.height * 0.10f).roundToInt())
            val left = (region.left + insetX).coerceIn(0, frame.width - 1)
            val right = (region.right - insetX).coerceIn(left + 1, frame.width)
            val top = (region.top + insetY).coerceIn(0, frame.height - 1)
            val bottom = (region.bottom - insetY).coerceIn(top + 1, frame.height)
            val width = ((right - left) + SAMPLE_STEP - 1) / SAMPLE_STEP
            val height = ((bottom - top) + SAMPLE_STEP - 1) / SAMPLE_STEP
            val colours = ArrayList<IntArray>(width * height)
            for (y in top until bottom step SAMPLE_STEP) for (x in left until right step SAMPLE_STEP) {
                val offset = y * frame.rowStrideBytes + x * 4
                colours += intArrayOf(frame.rgba8888[offset].toInt() and 255,
                    frame.rgba8888[offset + 1].toInt() and 255,
                    frame.rgba8888[offset + 2].toInt() and 255)
            }
            return SampledCell(width, height, colours)
        }

        private fun binDistance(left: Int, right: Int): Int = max(
            abs((left shr 8 and 0xF) - (right shr 8 and 0xF)),
            max(abs((left shr 4 and 0xF) - (right shr 4 and 0xF)),
                abs((left and 0xF) - (right and 0xF))),
        )

        private fun bin(colour: IntArray): Int =
            (colour[0] / BIN_SIZE shl 8) or (colour[1] / BIN_SIZE shl 4) or (colour[2] / BIN_SIZE)

        private fun colourDistance(left: IntArray, right: IntArray): Int = max(abs(left[0] - right[0]),
            max(abs(left[1] - right[1]), abs(left[2] - right[2])))

        private const val SAMPLE_STEP = 2
        private const val BIN_SIZE = 24
        private const val MATCH_DISTANCE = 38
        private const val CHANGE_DISTANCE = 24
        private const val MAX_COLOURS = 18
        private const val LOCAL_ANCHOR_SUPPORT = 0.12
        private const val LOCAL_GROUP_SUPPORT = 0.45
        private const val LOCAL_BIN_RADIUS = 3
        private const val LOCAL_MAX_COLOURS = 12
    }
}

internal data class CoveredPatternCell(
    val row: Int,
    val column: Int,
    val smoothness: Double,
    val meanR: Double,
    val meanG: Double,
    val meanB: Double,
)

/** Fits pure or diagonally repeating covered-cell colours without assuming the covered class is a majority. */
internal class CoveredPatternModel private constructor(
    private val period: Int,
    private val rowDirection: Int,
    private val phaseBins: Map<Int, Set<Int>>,
    val confidence: Double,
) {
    fun score(cell: CoveredPatternCell): Double {
        if (cell.smoothness < 0.60) return 0.0
        val phase = phase(cell.row, cell.column, period, rowDirection)
        val candidates = phaseBins[phase].orEmpty()
        if (colourBin(cell) !in candidates) return 0.0
        return (0.55 + cell.smoothness * 0.45) * confidence
    }

    companion object {
        fun learn(cells: List<CoveredPatternCell>, excluded: Set<Pair<Int, Int>>): CoveredPatternModel? {
            val smooth = cells.filter { it.smoothness >= 0.70 && (it.row to it.column) !in excluded }
            if (smooth.size < 6) return null
            var best: CoveredPatternModel? = null
            var bestQuality = 0.0
            for (period in 1..6) for (rowDirection in listOf(-1, 1)) {
                val byPhase = smooth.groupBy { phase(it.row, it.column, period, rowDirection) }
                if (byPhase.size < period || byPhase.values.any { it.isEmpty() }) continue
                val palettes = byPhase.mapValues { (_, phaseCells) ->
                    val counts = phaseCells.groupingBy(::colourBin).eachCount().entries.sortedByDescending { it.value }
                    val first = counts.firstOrNull() ?: return@mapValues emptySet()
                    counts.takeWhile { it.value >= max(1, (first.value * 0.35).roundToInt()) }
                        .take(2).mapTo(mutableSetOf()) { it.key }
                }
                val matched = smooth.count { colourBin(it) in palettes[phase(it.row, it.column, period, rowDirection)].orEmpty() }
                val support = matched.toDouble() / smooth.size
                val complexityPenalty = (period - 1) * 0.018
                val quality = support - complexityPenalty
                if (support >= 0.55 && quality > bestQuality) {
                    bestQuality = quality
                    best = CoveredPatternModel(period, rowDirection, palettes,
                        ((support - 0.45) / 0.50).coerceIn(0.25, 1.0))
                }
            }
            return best
        }

        private fun phase(row: Int, column: Int, period: Int, rowDirection: Int): Int =
            ((column + row * rowDirection) % period + period) % period

        private fun colourBin(cell: CoveredPatternCell): Int =
            (cell.meanR.toInt() / 32 shl 8) or (cell.meanG.toInt() / 32 shl 4) or (cell.meanB.toInt() / 32)
    }
}
