package com.bagridmaster.app.capture

import com.bagridmaster.app.vision.RgbaFrame

/** Transport sanity only, not game recognition. Never crop/stretch a broken capture into a board. */
internal object CaptureFrameQuality {
    fun problem(frame: RgbaFrame): String? {
        val columns = minOf(96, frame.width)
        val rows = minOf(54, frame.height)
        var visible = 0
        var minX = columns
        var maxX = -1
        var minY = rows
        var maxY = -1
        for (y in 0 until rows) for (x in 0 until columns) {
            val px = ((x + 0.5) * frame.width / columns).toInt()
            val py = ((y + 0.5) * frame.height / rows).toInt()
            val offset = py * frame.rowStrideBytes + px * 4
            val rgbMax = maxOf(frame.rgba8888[offset].toInt() and 255,
                frame.rgba8888[offset + 1].toInt() and 255, frame.rgba8888[offset + 2].toInt() and 255)
            if (rgbMax > 12) {
                visible++
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        if (visible == 0) return "捕获画面为黑屏（可能仍在切换画面，或内容禁止捕获）"
        val fraction = visible.toDouble() / (columns * rows)
        val narrowStrip = (maxX - minX + 1).toDouble() / columns < 0.25 ||
            (maxY - minY + 1).toDouble() / rows < 0.25
        val touchesEdge = minX <= 1 || minY <= 1 || maxX >= columns - 2 || maxY >= rows - 2
        return if (fraction < 0.18 && narrowStrip && touchesEdge) {
            "捕获画面不完整：大面积黑屏，内容仅在边缘（可见采样 ${"%.1f".format(fraction * 100)}%）"
        } else null
    }
}
