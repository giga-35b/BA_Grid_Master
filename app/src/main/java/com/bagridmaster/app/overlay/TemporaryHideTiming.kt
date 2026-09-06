package com.bagridmaster.app.overlay

/** Monotonic timeout plus MediaStore-ID restoration rule for a temporary overlay hide. */
internal class TemporaryHideTiming(startedAtMs: Long, durationSeconds: Int) {
    private val deadlineMs = startedAtMs + durationSeconds.coerceIn(1, 10) * 1_000L

    fun remainingMs(nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0)

    fun shouldRestore(nowMs: Long, previousMaxId: Long, currentMaxId: Long?): Boolean =
        remainingMs(nowMs) == 0L || (currentMaxId != null && currentMaxId > previousMaxId)
}
