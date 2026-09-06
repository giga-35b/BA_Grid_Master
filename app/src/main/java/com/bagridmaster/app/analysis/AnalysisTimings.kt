package com.bagridmaster.app.analysis

/** Monotonic wall times. Input wait/read is separate from vision and recommendation work. */
data class AnalysisTimings(
    val inputMs: Long = 0,
    val boardMs: Long = 0,
    val inventoryOcrMs: Long = 0,
    val templatePreparationMs: Long = 0,
    val objectRecognitionMs: Long = 0,
    val placementMatchingMs: Long = 0,
    val completionMs: Long = 0,
    val explorationMs: Long = 0,
) {
    val recognitionMs: Long get() = boardMs + inventoryOcrMs + templatePreparationMs + objectRecognitionMs + placementMatchingMs
    val recommendationMs: Long get() = completionMs + explorationMs
}

fun AnalysisResult.timingSummary(): String = timings?.let {
    "识别：${it.recognitionMs} ms · 推荐：${it.recommendationMs} ms"
} ?: "识别/推荐：未分段记录（总耗时 ${elapsedMs} ms）"

internal fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
