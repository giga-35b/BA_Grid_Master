package com.bagridmaster.app

import android.graphics.BitmapFactory
import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.vision.*

/** Optional ADB app_process entry point, not installed or used by the production app.
 * Uses only contracts shared with the previous APK so the exact old/new algorithms can be compared.
 * Excludes Android ML Kit OCR and capture wait; quantities are verified from the supplied fixture.
 */
object ShellPerformanceProbe {
    @JvmStatic fun main(args: Array<String>) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(args.single()))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            bytes[i * 4] = (pixels[i] shr 16).toByte()
            bytes[i * 4 + 1] = (pixels[i] shr 8).toByte()
            bytes[i * 4 + 2] = pixels[i].toByte()
            bytes[i * 4 + 3] = -1
        }
        val frame = RgbaFrame(bitmap.width, bitmap.height, bitmap.width * 4, bytes, 0)
        bitmap.recycle()
        repeat(3) { run ->
            var started = System.nanoTime()
            fun elapsed(): Long = ((System.nanoTime() - started) / 1_000_000).also { started = System.nanoTime() }
            val detector = GameVisionDetector()
            val raw = checkNotNull(detector.analyze(frame))
            val detected = checkNotNull(detector.reinspect(frame, raw, ScreenRegion(1147, 261, 2186, 838)))
            val boardMs = elapsed()
            val cards = detected.itemCards.mapIndexed { i, c -> c.copy(remainingCount = listOf(3, 4, 1)[i],
                templatePreview = LocalTemplateMatcher().preparePreview(c)) }
            val previewMs = elapsed()
            val completion = FragmentCompletionPlanner().analyze(frame, detected, cards)
            val matchMs = elapsed()
            val prepared = LitObjectExplorationGate().prepare(detected.geometry.board, detected.boardCells, cards, completion)
            val exploration = if (prepared.allowed) BoardStrategySolver().recommend(prepared.cells, prepared.inventory,
                StrategyAlgorithm.LIMITED_LOOKAHEAD,
                excluded = completion.recommendations.mapTo(mutableSetOf()) { GridCell(it.row, it.column) },
                knownLitFootprints = prepared.knownLitFootprints) else null
            val exploreMs = elapsed()
            val match = completion.objects.single { it.phase == BoardObjectPhase.LIT }.localMatch
            println("PHONE_PROFILE run=$run boardMs=$boardMs previewMs=$previewMs fragmentMs=$matchMs explorationMs=$exploreMs " +
                "match=$match suggestions=${(completion.recommendations + listOfNotNull(exploration)).map { cellAddress(it.row, it.column) }}")
        }
    }
}
