package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.RgbaPatch

/** Detect the game's yellow six-letter Finish label on its dark central banner.
 * No artwork name, inventory shape, previous frame, or round number is assumed.
 * OCR is a separate fallback; a dimmed card alone is never proof of completion.
 */
internal object FinishLabelDetector {
    fun detect(patch: RgbaPatch, trace: (String) -> Unit = {}): Boolean {
        val w = patch.width
        val h = patch.height
        if (w < 40 || h < 25) return false
        fun rgb(x: Int, y: Int): Int {
            val i = (y * w + x) * 4
            return ((patch.rgba8888[i].toInt() and 255) shl 16) or
                ((patch.rgba8888[i + 1].toInt() and 255) shl 8) or (patch.rgba8888[i + 2].toInt() and 255)
        }
        val yellow = BooleanArray(w * h)
        val xs = mutableListOf<Int>()
        val ys = mutableListOf<Int>()
        for (y in h / 5 until h * 4 / 5) for (x in w / 5 until w * 4 / 5) {
            val color = rgb(x, y)
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            if (r >= 175 && g >= 160 && minOf(r, g) - b >= 65) {
                yellow[y * w + x] = true
                xs += x; ys += y
            }
        }
        if (xs.size < 30) return false
        val left = xs.min(); val right = xs.max() + 1
        val top = ys.min(); val bottom = ys.max() + 1
        val width = right - left; val height = bottom - top
        trace("patch=${w}x$h word=${width}x$height yellow=${xs.size}")
        if (width.toDouble() / height !in 2.8..4.8 || width.toDouble() / w !in 0.28..0.58 ||
            height.toDouble() / h !in 0.10..0.32) return false
        // Six glyph columns, with the two narrow i's in positions 2 and 4.
        // At small screenshot sizes the arch of n can be only one pixel thick.
        // Requiring two pixels splits one real letter into two apparent glyphs.
        val columns = (left until right).map { x -> (top until bottom).any { y -> yellow[y * w + x] } }
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in 0..columns.size) {
            if (i < columns.size && columns[i]) { if (start < 0) start = i }
            else if (start >= 0) { runs += start until i; start = -1 }
        }
        trace("glyphs=${runs.map { it.count() }}")
        if (runs.size != 6) return false
        val widths = runs.map { it.count() }
        val broad = listOf(widths[0], widths[2], widths[4], widths[5]).average()
        trace("broad=$broad relativeHeight=${broad / height}")
        if (widths[1] > broad * 0.65 || widths[3] > broad * 0.65 || broad < height * 0.30) return false
        // The first glyph must look like F, not merely six yellow blobs on dark artwork.
        val fLeft = left + runs.first().first
        val fWidth = widths.first()
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var hits = 0; var count = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                count++; if (yellow[y * w + x]) hits++
            }
            return hits.toDouble() / count.coerceAtLeast(1)
        }
        trace("F stem=${fill(fLeft, top + height / 8, fLeft + (fWidth * 0.4).toInt().coerceAtLeast(1), bottom - height / 8)} top=${fill(fLeft + fWidth / 2, top, fLeft + fWidth, top + height / 3)} bottom=${fill(fLeft + fWidth / 2, top + height * 7 / 10, fLeft + fWidth, bottom)}")
        if (fill(fLeft, top + height / 8, fLeft + (fWidth * 0.4).toInt().coerceAtLeast(1), bottom - height / 8) < 0.50 ||
            fill(fLeft + fWidth / 2, top, fLeft + fWidth, top + height / 3) < 0.20 ||
            fill(fLeft + fWidth / 2, top + height * 7 / 10, fLeft + fWidth, bottom) > 0.20) return false
        // Check the banner around the word, not dark pixels of the object underneath.
        var dark = 0; var total = 0
        for (y in top until bottom) for (x in w / 12 until w * 11 / 12) {
            if (x in left - 2..right + 2) continue
            val color = rgb(x, y)
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            total++
            if (maxOf(r, g, b) < 110) dark++
        }
        trace("dark=${dark.toDouble() / total.coerceAtLeast(1)}")
        return total > 0 && dark.toDouble() / total >= 0.90
    }
}
