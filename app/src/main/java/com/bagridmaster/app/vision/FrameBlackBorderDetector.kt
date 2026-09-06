package com.bagridmaster.app.vision

/** Thickness of near-solid black strips connected to the four outer image edges. */
data class FrameBlackBorders(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val exceedsCalibrationThreshold: Boolean
        get() = maxOf(left, top, right, bottom) >= BLACK_BORDER_TRIGGER_PX

    fun label(): String = "左${left}px、上${top}px、右${right}px、下${bottom}px"
}

/**
 * Detects letterboxing rather than dark scene content: a line must be almost entirely black,
 * and qualifying lines must continue inwards from the physical image edge.
 */
object FrameBlackBorderDetector {
    fun inspect(frame: RgbaFrame): FrameBlackBorders = FrameBlackBorders(
        left = verticalThickness(frame, fromLeft = true),
        top = horizontalThickness(frame, fromTop = true),
        right = verticalThickness(frame, fromLeft = false),
        bottom = horizontalThickness(frame, fromTop = false),
    )

    private fun verticalThickness(frame: RgbaFrame, fromLeft: Boolean): Int {
        val limit = (frame.width / MAX_SCAN_DIVISOR).coerceAtLeast(BLACK_BORDER_TRIGGER_PX).coerceAtMost(frame.width)
        var thickness = 0
        for (offset in 0 until limit) {
            val x = if (fromLeft) offset else frame.width - 1 - offset
            var black = 0
            for (y in 0 until frame.height) if (isBlack(frame, x, y)) black++
            if (black.toDouble() / frame.height < MIN_BLACK_LINE_RATIO) break
            thickness++
        }
        return thickness
    }

    private fun horizontalThickness(frame: RgbaFrame, fromTop: Boolean): Int {
        val limit = (frame.height / MAX_SCAN_DIVISOR).coerceAtLeast(BLACK_BORDER_TRIGGER_PX).coerceAtMost(frame.height)
        var thickness = 0
        for (offset in 0 until limit) {
            val y = if (fromTop) offset else frame.height - 1 - offset
            var black = 0
            for (x in 0 until frame.width) if (isBlack(frame, x, y)) black++
            if (black.toDouble() / frame.width < MIN_BLACK_LINE_RATIO) break
            thickness++
        }
        return thickness
    }

    private fun isBlack(frame: RgbaFrame, x: Int, y: Int): Boolean {
        val offset = y * frame.rowStrideBytes + x * 4
        return (frame.rgba8888[offset].toInt() and 0xFF) <= BLACK_CHANNEL_MAX &&
            (frame.rgba8888[offset + 1].toInt() and 0xFF) <= BLACK_CHANNEL_MAX &&
            (frame.rgba8888[offset + 2].toInt() and 0xFF) <= BLACK_CHANNEL_MAX
    }

    private const val BLACK_CHANNEL_MAX = 28
    private const val MIN_BLACK_LINE_RATIO = 0.96
    private const val MAX_SCAN_DIVISOR = 4
}

const val BLACK_BORDER_TRIGGER_PX = 5
