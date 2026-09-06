package com.bagridmaster.app.overlay

/** Monotonic deadlines: recognition readiness and panel dimming are independent. */
internal class GalleryCaptureTiming {
    private var startedAtMs: Long? = null

    fun start(nowMs: Long) { startedAtMs = nowMs }
    fun reset() { startedAtMs = null }

    fun readyDelayMs(nowMs: Long): Long = remaining(nowMs, 500)
    fun dimDelayMs(nowMs: Long): Long = remaining(nowMs, 3_000)

    fun alpha(nowMs: Long, configuredAlpha: Float): Float =
        if (dimDelayMs(nowMs) > 0) 0.10f else configuredAlpha

    private fun remaining(nowMs: Long, durationMs: Long): Long =
        startedAtMs?.let { (it + durationMs - nowMs).coerceAtLeast(0) } ?: 0L
}
