package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import kotlin.math.*

/** Normalize a non-square sprite's long axis BEFORE dividing it into logical cells.
 * Square artwork has no unique pre-rotation; its placement is resolved from grid-edge evidence.
 */
class LocalTemplateMatcher {
    /** Runs for every inventory card, even on an entirely unopened board. Uses the same
     * renderer as matching; the displayed horizontal reference is not the winning placement.
     */
    fun preparePreview(card: ItemCardRecognition): TemplatePreview {
        val shape = card.shape
        val currentCropIsFinished = card.isFinished && FinishLabelDetector.detect(card.spriteTemplate)
        if (shape == null || shape.cellCount != shape.rows * shape.columns || shape.cellCount !in 1..45 || currentCropIsFinished) {
            return TemplatePreview(0, 0, null, null, emptyList(), "形状缺失或物品已完成，不强行预处理")
        }
        if (shape.rows == shape.columns) return TemplatePreview(shape.rows, shape.columns, null, null, emptyList(),
            "正方形不预旋转/分割匹配；检测点亮后用边界延伸方向定位")
        val source = Source(card.spriteTemplate)
        val rows = minOf(shape.rows, shape.columns)
        val columns = maxOf(shape.rows, shape.columns)
        val angle = source.horizontalAngle
        // Display detail is independent of the fixed, inexpensive matching resolution.
        val template = source.render(rows, columns, angle, 1.0, samples = PREVIEW_SAMPLES)
            ?: return TemplatePreview(rows, columns, angle, null, emptyList(), "前景不足或形状比例不符，无法生成可信预览")
        fun patch(left: Int, top: Int, width: Int, height: Int): RgbaPatch {
            val bytes = ByteArray(width * height * 4)
            for (y in 0 until height) for (x in 0 until width) {
                val rgb = template.pixels[(top + y) * template.width + left + x]
                val offset = (y * width + x) * 4
                bytes[offset] = (rgb shr 16).toByte(); bytes[offset + 1] = (rgb shr 8).toByte()
                bytes[offset + 2] = rgb.toByte()
                bytes[offset + 3] = (rgb ushr 24).toByte()
            }
            return RgbaPatch(width, height, bytes)
        }
        return TemplatePreview(rows, columns, angle, patch(0, 0, template.width, template.height),
            (0 until rows).flatMap { r -> (0 until columns).map { c -> patch(c * PREVIEW_SAMPLES, r * PREVIEW_SAMPLES, PREVIEW_SAMPLES, PREVIEW_SAMPLES) } },
            "顺时针 $angle°，归一化为 ${columns}×${rows}；按行分格。" +
                if (source.axisAmbiguous) "长轴不明确，匹配仍搜索多个角度；预览仅为参考，不代表摆放。"
                else "匹配另检查180°反向、横竖摆放和小角度修正；预览不代表摆放。")
    }

    fun match(
        frame: RgbaFrame,
        board: BoardGeometry,
        cells: List<BoardCellObservation>,
        item: BoardObjectObservation,
        cards: List<ItemCardRecognition>,
    ): LocalTemplateMatch {
        val relevant = cards.filter { it.index in item.possibleItemIndices }
        val hasSquareCandidate = relevant.any { it.shape?.let { s -> s.rows == s.columns } == true }
        val plausible = relevant.filter { it.shape?.let { s -> s.rows != s.columns } == true }
        if (plausible.isEmpty()) return LocalTemplateMatch(false, emptyList(), 0.0,
            if (hasSquareCandidate) "正方形物品不做旋转贴图匹配，使用延伸方向与合法摆放定位" else "缺少可用形状，不强行进行贴图切分")
        val placements = legalObjectPlacements(board, cells.associate { GridCell(it.row, it.column) to it.state }, item.observedCells, plausible)
        if (placements.isEmpty()) return LocalTemplateMatch(false, emptyList(), 0.0, "没有可对齐的完整矩形贴图或合法摆法")
        val observations = item.observedCells.sortedWith(compareBy({ it.row }, { it.column })).map { cell ->
            val region = checkNotNull(board.cellRegion(cell.row, cell.column))
            ObservedCell(cell, IntArray(SAMPLES * SAMPLES) { i ->
                val x = (region.left + (i % SAMPLES + 0.5) * region.width / SAMPLES).toInt().coerceIn(0, frame.width - 1)
                val y = (region.top + (i / SAMPLES + 0.5) * region.height / SAMPLES).toInt().coerceIn(0, frame.height - 1)
                rgb(frame.rgba8888, y * frame.rowStrideBytes + x * 4)
            })
        }
        val ranked = mutableListOf<TemplatePlacementMatch>()
        for (card in plausible) {
            val source = Source(card.spriteTemplate)
            if (source.points.size < 24) continue
            for (size in placements.filter { it.itemIndex == card.index }.groupBy { it.rows to it.columns }) {
                val (rows, columns) = size.key
                val best = mutableMapOf<ObjectPlacement, Alignment>()
                for (angle in source.alignmentAngles(rows, columns)) {
                    for (fill in listOf(0.90, 1.0, 1.10)) {
                        val template = source.render(rows, columns, angle, fill) ?: continue
                        for (placement in size.value) {
                            for (dy in listOf(-2, 0, 2)) for (dx in listOf(-2, 0, 2)) {
                                val score = compare(template, placement, observations, dx, dy)
                                if (score.score > (best[placement]?.score?.score ?: 0.0)) {
                                    best[placement] = Alignment(angle, fill, dx, dy, score)
                                }
                            }
                        }
                    }
                }
                // Refine only the strongest placements; work is bounded independently of screen pixels.
                for ((placement, coarse) in best.entries.sortedByDescending { it.value.score.score }.take(4)) {
                    var refined = coarse
                    for (delta in -12..12 step 3) {
                        val angle = (coarse.angle + delta + 360) % 360
                        for (fill in listOf(coarse.fill - 0.05, coarse.fill, coarse.fill + 0.05)) {
                            for (crossFill in listOf(0.90, 1.0, 1.10)) {
                                val template = source.render(rows, columns, angle, fill, crossFill) ?: continue
                                for (dy in coarse.dy - 1..coarse.dy + 1) for (dx in coarse.dx - 1..coarse.dx + 1) {
                                    val score = compare(template, placement, observations, dx, dy)
                                    if (score.score > refined.score.score) refined = Alignment(angle, fill, dx, dy, score)
                                }
                            }
                        }
                    }
                    ranked += TemplatePlacementMatch(card.index, placement.origin, rows, columns, refined.angle,
                        refined.score.score, refined.score.mask, refined.score.texture, source.horizontalAngle)
                }
            }
        }
        val candidates = ranked.sortedByDescending { it.similarity }
        val best = candidates.firstOrNull() ?: return LocalTemplateMatch(false, emptyList(), 0.0, "贴图没有足够的有效前景，未匹配")
        val margin = best.similarity - (candidates.getOrNull(1)?.similarity ?: 0.0)
        // A subpixel grid can move a sampled cell boundary by one pixel after resizing. Permit a
        // very narrow score shoulder only when silhouette agreement and candidate separation are
        // both exceptionally strong; ordinary ambiguous candidates still use the main threshold.
        val subpixelBoundaryPass = best.similarity >= MIN_SUBPIXEL_SCORE &&
            best.maskSimilarity >= MIN_SUBPIXEL_MASK && margin >= MIN_SUBPIXEL_MARGIN
        val scorePass = best.similarity >= MIN_SCORE || subpixelBoundaryPass
        val accepted = !hasSquareCandidate && scorePass && best.maskSimilarity >= MIN_MASK && margin >= MIN_MARGIN
        return LocalTemplateMatch(accepted, candidates.take(3), margin, when {
            hasSquareCandidate -> "种类仍可能为正方形，不能仅在非正方形候选中选择摆法"
            !scorePass || best.maskSimilarity < MIN_MASK -> "纹理/轮廓吻合不足，未采用局部位置"
            margin < MIN_MARGIN -> "存在分数接近的不同摆法，局部位置不确定"
            else -> "长轴预旋转、按形状归一化切分后，空间轮廓/颜色/明暗纹理对齐通过；覆盖范围仍为推定"
        })
    }

    private data class ObservedCell(val cell: GridCell, val pixels: IntArray) {
        val foregroundMask = BooleanArray(pixels.size) { foreground(pixels[it]) }
        val luminance = smoothedLuminance(pixels, SAMPLES, SAMPLES, foregroundMask)
    }
    private data class Score(val score: Double, val mask: Double, val texture: Double)
    private data class Alignment(val angle: Int, val fill: Double, val dx: Int, val dy: Int, val score: Score)
    private data class Template(val width: Int, val height: Int, val pixels: IntArray) {
        // Full cutout alpha retains dark artwork. Score only the same reliable colour
        // evidence used by ObservedCell, so extra dark detail is not a false mask penalty.
        val foregroundMask = BooleanArray(pixels.size) { (pixels[it] ushr 24) >= 128 && foreground(pixels[it]) }
        val luminance = smoothedLuminance(pixels, width, height, foregroundMask)
    }

    private class Source(private val patch: RgbaPatch) {
        private val mask = FragmentPixels.templateMask(patch)
        val points = mask.indices.filter { mask[it] }
        private val axis: Pair<Int, Double> = run {
            if (points.isEmpty()) 0 to 1.0 else {
                val cx = points.map { it % patch.width }.average()
                val cy = points.map { it / patch.width }.average()
                var xx = 0.0; var yy = 0.0; var xy = 0.0
                for (p in points) {
                    val x = p % patch.width - cx; val y = p / patch.width - cy
                    xx += x * x; yy += y * y; xy += x * y
                }
                val delta = sqrt((xx - yy).pow(2) + 4 * xy * xy)
                val ratio = (xx + yy + delta) / (xx + yy - delta).coerceAtLeast(1.0)
                val angle = (-Math.toDegrees(atan2(2 * xy, xx - yy)) / 2).roundToInt()
                ((angle % 180 + 180) % 180) to ratio
            }
        }
        // A long axis is directionless: 175° and -5° describe the same alignment. Present and
        // store the shortest correction so the reported pre-rotation is always within ±90°.
        val horizontalAngle: Int get() = canonicalPreRotationDegrees(axis.first)
        val axisAmbiguous: Boolean get() = axis.second < 1.4

        private data class RotatedBounds(val cos: Double, val sin: Double,
            val minX: Double, val maxX: Double, val minY: Double, val maxY: Double)
        private data class RenderKey(val rows: Int, val columns: Int, val angle: Int, val fill: Double, val crossFill: Double, val samples: Int)
        private val boundsByAngle = mutableMapOf<Int, RotatedBounds>()
        // Per sprite, per match only. Repeated refinement hypotheses share identical pixels.
        // Account conservatively for RGB, foreground and luminance arrays; no global image cache.
        private val templates = LinkedHashMap<RenderKey, Template>(96, 0.75f, true)
        private var cachedBytes = 0

        fun alignmentAngles(rows: Int, columns: Int): List<Int> {
            // Near-isotropic artwork (e.g. spiky 2x3 items) cannot determine its long axis
            // reliably. Retain several geometric alignments instead of inventing one angle.
            val horizontal = if (axis.second < 1.4) (0 until 180 step 15).toList()
                else (-18..18 step 6).map { horizontalAngle + it }
            return horizontal.flatMap { a -> listOf(a, a + 180) }
                .map { (it + (if (rows > columns) 90 else 0) + 360) % 360 }.distinct()
        }

        fun render(rows: Int, columns: Int, angle: Int, fill: Double, crossFill: Double = 1.0, samples: Int = SAMPLES): Template? {
            if (points.isEmpty()) return null
            val key = RenderKey(rows, columns, angle, fill, crossFill, samples)
            templates[key]?.let { return it }
            val bounds = boundsByAngle.getOrPut(angle) { rotatedBounds(angle) }
            val (cos, sin, minX, maxX, minY, maxY) = bounds
            val width = columns * samples
            val height = rows * samples
            // Inventory artwork and board artwork need not have exactly the same aspect.
            // Normalize the foreground bounds to the known footprint, with bounded stretch.
            val sx = width / (maxX - minX + 1)
            val sy = height / (maxY - minY + 1)
            if (sx / sy !in 0.60..1.67) return null
            val scaleX = sx * fill * if (rows > columns) crossFill else 1.0
            val scaleY = sy * fill * if (columns > rows) crossFill else 1.0
            val pixels = IntArray(width * height) { i ->
                val rx = (i % width + 0.5 - width / 2.0) / scaleX + (minX + maxX) / 2
                val ry = (i / width + 0.5 - height / 2.0) / scaleY + (minY + maxY) / 2
                sample(cos * rx + sin * ry + patch.width / 2.0,
                    -sin * rx + cos * ry + patch.height / 2.0)
            }
            val template = Template(width, height, pixels)
            val bytes = pixels.size * 16 + 256
            while (cachedBytes + bytes > 8 * 1024 * 1024 && templates.isNotEmpty()) {
                val oldest = templates.entries.iterator()
                cachedBytes -= oldest.next().value.pixels.size * 16 + 256
                oldest.remove()
            }
            if (bytes <= 8 * 1024 * 1024) {
                templates[key] = template
                cachedBytes += bytes
            }
            return template
        }

        private fun rotatedBounds(angle: Int): RotatedBounds {
            val radians = Math.toRadians(angle.toDouble())
            val cos = cos(radians)
            val sin = sin(radians)
            var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
            for (point in points) {
                val x = point % patch.width - patch.width / 2.0
                val y = point / patch.width - patch.height / 2.0
                val rx = cos * x - sin * y
                val ry = sin * x + cos * y
                minX = min(minX, rx); maxX = max(maxX, rx)
                minY = min(minY, ry); maxY = max(maxY, ry)
            }
            return RotatedBounds(cos, sin, minX, maxX, minY, maxY)
        }

        private fun sample(x: Double, y: Double): Int {
            val ix = floor(x).toInt(); val iy = floor(y).toInt()
            if (ix !in 0 until patch.width - 1 || iy !in 0 until patch.height - 1) return 0
            val p = iy * patch.width + ix
            val fx = x - ix; val fy = y - iy
            // Interpolate the mask with the image, excluding background colour from RGB.
            // Re-thresholding RGB here would punch the original dark markings out again.
            val w00 = if (mask[p]) (1 - fx) * (1 - fy) else 0.0
            val w10 = if (mask[p + 1]) fx * (1 - fy) else 0.0
            val w01 = if (mask[p + patch.width]) (1 - fx) * fy else 0.0
            val w11 = if (mask[p + patch.width + 1]) fx * fy else 0.0
            val alpha = w00 + w10 + w01 + w11
            if (alpha <= 0.0) return 0
            var result = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24)
            for (channel in 0..2) {
                fun value(dx: Int, dy: Int) = patch.rgba8888[((iy + dy) * patch.width + ix + dx) * 4 + channel].toInt() and 255
                val value = ((value(0, 0) * w00 + value(1, 0) * w10 +
                    value(0, 1) * w01 + value(1, 1) * w11) / alpha).roundToInt().coerceIn(0, 255)
                result = result or (value shl (16 - channel * 8))
            }
            return result
        }
    }

    private fun compare(template: Template, placement: ObjectPlacement, observations: List<ObservedCell>, dx: Int, dy: Int): Score {
        var observedCount = 0; var templateCount = 0; var overlap = 0
        var difference = 0.0
        var sumO = 0.0; var sumT = 0.0; var sumOO = 0.0; var sumTT = 0.0; var sumOT = 0.0
        for (observed in observations) {
            val left = (observed.cell.column - placement.origin.column) * SAMPLES
            val top = (observed.cell.row - placement.origin.row) * SAMPLES
            // Ignore the game's grid line and selection border, not a wide interior strip.
            for (y in 2 until SAMPLES - 2) for (x in 2 until SAMPLES - 2) {
                val actual = observed.pixels[y * SAMPLES + x]
                val tx = left + x - dx; val ty = top + y - dy
                val inside = tx in 0 until template.width && ty in 0 until template.height
                val reference = if (inside) template.pixels[ty * template.width + tx] else 0
                val a = observed.foregroundMask[y * SAMPLES + x]
                val b = inside && template.foregroundMask[ty * template.width + tx]
                if (a) observedCount++
                if (b) templateCount++
                if (!a || !b) continue
                overlap++
                val ar = actual shr 16 and 255; val ag = actual shr 8 and 255; val ab = actual and 255
                val br = reference shr 16 and 255; val bg = reference shr 8 and 255; val bb = reference and 255
                difference += (abs(ar - br) + abs(ag - bg) + abs(ab - bb)) / 765.0
                val ao = observed.luminance[y * SAMPLES + x]
                val bo = template.luminance[ty * template.width + tx]
                sumO += ao; sumT += bo; sumOO += ao * ao; sumTT += bo * bo; sumOT += ao * bo
            }
        }
        if (overlap < 16 || observedCount < 24) return Score(0.0, 0.0, 0.0)
        val mask = 2.0 * overlap / (observedCount + templateCount).coerceAtLeast(1)
        val covariance = sumOT - sumO * sumT / overlap
        val varianceO = (sumOO - sumO * sumO / overlap).coerceAtLeast(0.0)
        val varianceT = (sumTT - sumT * sumT / overlap).coerceAtLeast(0.0)
        val texture = if (min(varianceO, varianceT) / overlap >= 12.0) {
            (covariance / sqrt(varianceO * varianceT)).coerceIn(0.0, 1.0)
        } else 0.0
        val color = (1.0 - difference / overlap * 3).coerceIn(0.0, 1.0)
        return Score(0.45 * mask + 0.25 * color + 0.30 * texture, mask, texture)
    }

    companion object {
        private const val SAMPLES = 32
        private const val PREVIEW_SAMPLES = 96
        private const val MIN_SCORE = 0.78
        private const val MIN_MASK = 0.72
        private const val MIN_MARGIN = 0.045
        private const val MIN_SUBPIXEL_SCORE = 0.77
        private const val MIN_SUBPIXEL_MASK = 0.90
        private const val MIN_SUBPIXEL_MARGIN = 0.12

        /** Small foreground-only Gaussian window suppresses screenshot resampling/JPEG
         * noise without confusing the background or silhouette boundary with texture.
         * Raw pixel colours and the unsmoothed foreground mask remain separate checks.
         */
        private fun smoothedLuminance(pixels: IntArray, width: Int, height: Int, valid: BooleanArray): DoubleArray {
            val raw = DoubleArray(pixels.size) { i ->
                ((pixels[i] shr 16 and 255) + (pixels[i] shr 8 and 255) + (pixels[i] and 255)) / 3.0
            }
            return DoubleArray(pixels.size) { i ->
                if (!valid[i]) 0.0 else {
                    val x = i % width; val y = i / width
                    var sum = 0.0; var weight = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        if (x + dx !in 0 until width || y + dy !in 0 until height) continue
                        val p = (y + dy) * width + x + dx
                        if (!valid[p]) continue
                        val w = (if (dx == 0) 2 else 1) * (if (dy == 0) 2 else 1)
                        sum += raw[p] * w; weight += w
                    }
                    sum / weight.coerceAtLeast(1)
                }
            }
        }

        private fun rgb(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 255) shl 16) or ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)

        private fun foreground(rgb: Int): Boolean = FragmentPixels.foreground(rgb)
    }
}

internal fun canonicalPreRotationDegrees(angle: Int): Int {
    val modulo = ((angle % 180) + 180) % 180
    return if (modulo > 90) modulo - 180 else modulo
}
