package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ScreenRegion

/** Finds the embedded game viewport after a strict edge-black test has detected letterboxing. */
object ActiveViewportDetector {
    fun detect(frame: RgbaFrame): ScreenRegion {
        val left = sampledMedian((0 until frame.height step sampleStep(frame.height)).map { y ->
            blackRun(frame.width) { x -> isBlack(frame, x, y) }
        })
        val right = sampledMedian((0 until frame.height step sampleStep(frame.height)).map { y ->
            blackRun(frame.width) { offset -> isBlack(frame, frame.width - 1 - offset, y) }
        })
        val top = sampledMedian((0 until frame.width step sampleStep(frame.width)).map { x ->
            blackRun(frame.height) { y -> isBlack(frame, x, y) }
        })
        val bottom = sampledMedian((0 until frame.width step sampleStep(frame.width)).map { x ->
            blackRun(frame.height) { offset -> isBlack(frame, x, frame.height - 1 - offset) }
        })
        return ScreenRegion(left, top, frame.width - right, frame.height - bottom).takeIf {
            it.width >= frame.width / 2 && it.height >= frame.height / 2
        } ?: ScreenRegion(0, 0, frame.width, frame.height)
    }

    private fun blackRun(limit: Int, predicate: (Int) -> Boolean): Int {
        var result = 0
        while (result < limit / 3 && predicate(result)) result++
        return result
    }

    private fun sampledMedian(values: List<Int>): Int = values.sorted().let { sorted ->
        sorted.getOrElse(sorted.size / 2) { 0 }
    }

    private fun sampleStep(length: Int) = (length / 48).coerceAtLeast(1)

    private fun isBlack(frame: RgbaFrame, x: Int, y: Int): Boolean {
        val offset = y * frame.rowStrideBytes + x * 4
        return (frame.rgba8888[offset].toInt() and 0xFF) <= 28 &&
            (frame.rgba8888[offset + 1].toInt() and 0xFF) <= 28 &&
            (frame.rgba8888[offset + 2].toInt() and 0xFF) <= 28
    }
}
