package com.bagridmaster.app.vision

/** Shared foreground evidence for presence, appearance, alignment and boundary contact.
 * Pale warm/neutral artwork must not disappear merely because it has little chroma.
 * Cyan/blue coral and the blue-grey completed silhouettes are not lit foreground.
 */
internal object FragmentPixels {
    fun chromatic(r: Int, g: Int, b: Int): Boolean =
        maxOf(r, g, b) >= 95 && maxOf(r, g, b) - minOf(r, g, b) >= 48 &&
            (maxOf(r, g) - b >= 18 || (r - g >= 25 && b - g >= 20 && r >= 80))

    fun pale(r: Int, g: Int, b: Int): Boolean =
        !chromatic(r, g, b) && r >= 125 && g >= 110 && b >= 90 &&
            ((r - b >= 8 && r >= g + 1) || (r >= 175 && b <= r + 4 && g <= r + 5))

    fun foreground(r: Int, g: Int, b: Int) = chromatic(r, g, b) || pale(r, g, b)
    fun foreground(rgb: Int) = foreground(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255)

    const val APPEARANCE_BINS = 15

    /** Inventory artwork is a cutout, not board foreground evidence. Estimate its background
     * from an inset perimeter (avoiding the card frame), regardless of the event's theme.
     * A robust RGB centre rejects outliers; the sampled colour range includes darker grid
     * lines. No particular hue is assumed and genuine gaps between sprite parts stay empty.
     * This runs once per source crop, never once per rotation/search hypothesis.
     */
    fun templateMask(patch: com.bagridmaster.app.analysis.RgbaPatch): BooleanArray {
        if (patch.width < 8 || patch.height < 8) return BooleanArray(patch.width * patch.height)
        val inset = maxOf(2, minOf(patch.width, patch.height) / 12)
        val samples = mutableListOf<Int>()
        for (x in inset until patch.width - inset) {
            samples += inset * patch.width + x
            samples += (patch.height - 1 - inset) * patch.width + x
        }
        for (y in inset until patch.height - inset) {
            samples += y * patch.width + inset
            samples += y * patch.width + patch.width - 1 - inset
        }
        fun channel(i: Int, c: Int) = patch.rgba8888[i * 4 + c].toInt() and 255
        val background = IntArray(3) { c -> samples.map { channel(it, c) }.sorted()[samples.size / 2] }
        fun distance(i: Int) = maxOf(kotlin.math.abs(channel(i, 0) - background[0]),
            kotlin.math.abs(channel(i, 1) - background[1]), kotlin.math.abs(channel(i, 2) - background[2]))
        val inliers = samples.filter { distance(it) <= 64 }
        // No stable perimeter colour: do not invent a cutout from a contaminated crop.
        if (inliers.size < samples.size * 0.6) return BooleanArray(patch.width * patch.height)
        val low = IntArray(3) { c -> inliers.minOf { channel(it, c) } - 8 }
        val high = IntArray(3) { c -> inliers.maxOf { channel(it, c) } + 8 }
        val mask = BooleanArray(patch.width * patch.height) { i ->
            channel(i, 3) >= 128 && (channel(i, 0) !in low[0]..high[0] ||
                channel(i, 1) !in low[1]..high[1] || channel(i, 2) !in low[2]..high[2])
        }
        val remaining = mask.copyOf()
        val components = mutableListOf<IntArray>()
        val queue = IntArray(mask.size)
        for (start in remaining.indices) {
            if (!remaining[start]) continue
            var head = 0; var tail = 1
            queue[0] = start; remaining[start] = false
            while (head < tail) {
                val i = queue[head++]
                for (dy in -1..1) for (dx in -1..1) {
                    val x = i % patch.width + dx; val next = i + dy * patch.width + dx
                    if (x !in 0 until patch.width || next !in remaining.indices || !remaining[next]) continue
                    remaining[next] = false; queue[tail++] = next
                }
            }
            components += queue.copyOf(tail)
        }
        val largest = components.maxByOrNull { it.size } ?: return mask
        val clean = BooleanArray(mask.size)
        for (component in components) {
            val touchesBorder = component.any { it % patch.width < 2 || it % patch.width >= patch.width - 2 ||
                it / patch.width < 2 || it / patch.width >= patch.height - 2 }
            if (component === largest || (!touchesBorder && component.size >= maxOf(6, largest.size / 30))) {
                for (i in component) clean[i] = true
            }
        }
        return clean
    }

    fun appearanceBin(r: Int, g: Int, b: Int): Int? {
        if (!foreground(r, g, b)) return null
        if (!chromatic(r, g, b)) return when {
            r - b >= 18 -> 12 // cream / beige
            r - b >= 8 -> 13
            else -> 14 // near-white, not a fabricated saturated hue
        }
        val maximum = maxOf(r, g, b)
        val delta = maximum - minOf(r, g, b)
        val hue = when (maximum) {
            r -> (g - b).toDouble() / delta
            g -> 2.0 + (b - r).toDouble() / delta
            else -> 4.0 + (r - g).toDouble() / delta
        }
        return (((hue + 6) % 6) * 2).toInt().coerceIn(0, 11)
    }

    /** Reject isolated compression noise; coordinates are on the sampled cell grid. */
    fun largestComponent(mask: BooleanArray, width: Int): Int {
        if (width <= 0 || mask.isEmpty()) return 0
        val remaining = mask.copyOf()
        val queue = IntArray(mask.size)
        var largest = 0
        for (start in remaining.indices) {
            if (!remaining[start]) continue
            var head = 0; var tail = 1
            queue[0] = start; remaining[start] = false
            while (head < tail) {
                val i = queue[head++]
                for (dy in -1..1) for (dx in -1..1) {
                    val x = i % width + dx; val next = i + dy * width + dx
                    if (x !in 0 until width || next !in remaining.indices || !remaining[next]) continue
                    remaining[next] = false; queue[tail++] = next
                }
            }
            largest = maxOf(largest, tail)
        }
        return largest
    }
}
