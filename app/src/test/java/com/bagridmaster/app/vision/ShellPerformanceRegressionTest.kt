package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.model.StrategyAlgorithm
import org.junit.Assert.*
import org.junit.Test

/** Exact 2400px gallery input from the 8451ms report. Desktop timings are not phone timings. */
class ShellPerformanceRegressionTest {
    @Test fun profileExactGalleryInputWithoutChangingRecommendations() {
        val frame = LocalTemplateMatcherTest.readFrame("app/src/test/resources/shell-performance-2400.jpg")
        repeat(3) { run ->
            var started = System.nanoTime()
            fun elapsed(): Long = ((System.nanoTime() - started) / 1_000_000).also { started = System.nanoTime() }
            val detector = GameVisionDetector()
            val raw = checkNotNull(detector.analyze(frame))
            val detection = checkNotNull(detector.reinspect(frame, raw, ScreenRegion(1147, 261, 2186, 838)))
            val cvMs = elapsed()
            // OCR is Android-only; counts are the verified values in the supplied image.
            val cards = detection.itemCards.mapIndexed { i, c -> c.copy(remainingCount = listOf(3, 4, 1)[i],
                templatePreview = LocalTemplateMatcher().preparePreview(c)) }
            val previewMs = elapsed()
            val completion = FragmentCompletionPlanner().analyze(frame, detection, cards)
            val fragmentMs = elapsed()
            val prepared = LitObjectExplorationGate().prepare(detection.geometry.board, detection.boardCells, cards, completion)
            assertTrue(prepared.allowed)
            val exploration = BoardStrategySolver().recommend(prepared.cells, prepared.inventory,
                StrategyAlgorithm.LIMITED_LOOKAHEAD,
                excluded = completion.recommendations.mapTo(mutableSetOf()) { GridCell(it.row, it.column) },
                knownLitFootprints = prepared.knownLitFootprints)
            val explorationMs = elapsed()
            val match = checkNotNull(completion.objects.single { it.phase == BoardObjectPhase.LIT }.localMatch)
            println("SHELL_PROFILE run=$run cv=${cvMs}ms preview=${previewMs}ms fragment=${fragmentMs}ms exploration=${explorationMs}ms stages=${completion.timings} candidates=${match.candidates}")
            assertTrue(match.accepted)
            // Cutout/alpha improvements legitimately change scores and angle estimates.
            // Preserve the ground-truth footprint, strong evidence and final recommendations.
            assertTrue(match.candidates.first().similarity >= 0.90)
            assertTrue(match.margin >= 0.15)
            assertEquals(ObjectPlacement(2, GridCell(1, 7), 4, 2).cells, match.candidates.first().cells)
            assertEquals(setOf("I2", "H3", "I3", "H4", "I4", "H5", "I5"),
                completion.recommendations.map { cellAddress(it.row, it.column) }.toSet())
            assertNotNull(exploration)
            assertEquals("B4", cellAddress(exploration!!.row, exploration.column))
        }
    }
}
