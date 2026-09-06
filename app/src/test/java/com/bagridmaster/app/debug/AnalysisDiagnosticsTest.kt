package com.bagridmaster.app.debug

import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.vision.*
import org.junit.Assert.*
import org.junit.Test

class AnalysisDiagnosticsTest {
    private fun result() = AnalysisResult(emptyList(), 999, ResultSource.GALLERY,
        RecognitionGeometry(BoardGeometry(ScreenRegion(100, 100, 1000, 600), 5, 9, .99), null, "CV"))

    @Test fun stageTotalsDoNotCountInputOrPlacementAsRecommendation() {
        val t = AnalysisTimings(inputMs = 500, boardMs = 10, inventoryOcrMs = 20,
            templatePreparationMs = 30, objectRecognitionMs = 40, placementMatchingMs = 50,
            completionMs = 60, explorationMs = 70)
        val result = result().copy(timings = t)
        assertEquals(150L, t.recognitionMs)
        assertEquals(130L, t.recommendationMs)
        assertEquals("识别：150 ms · 推荐：130 ms", result.timingSummary())
        assertTrue(result.debugReport().contains("采集/等候：500 ms · 总耗时：999 ms"))
        assertTrue(result.timingLogLine().contains("placementMs=50"))
        assertTrue(result.timingLogLine().contains("recommendationMs=130"))
    }

    @Test fun unmeasuredLegacyResultsNeverPretendToHaveZeroStageTimes() {
        assertEquals("识别/推荐：未分段记录（总耗时 999 ms）", result().timingSummary())
        assertTrue(result().timingLogLine().contains("timings=unavailable"))
    }

    @Test fun all45CellJudgmentsAreLoggedButNotShownInReport() {
        val cells = (0 until 5).flatMap { r -> (0 until 9).map { c ->
            BoardCellObservation(r, c, BoardCellState.CLOSED,
                CellPresenceEvidence(.01, .02, .03, .04, .05, "evidence-$r-$c"))
        } }
        val result = result().copy(boardCells = cells)
        val lines = result.cellDiagnosticLines()
        assertEquals(45, lines.size)
        assertTrue(lines.first().startsWith("A1 state=CLOSED"))
        assertTrue(lines.last().startsWith("I5 state=CLOSED"))
        assertTrue(lines.all { it.contains("chromatic=0.01 pale=0.02 foreground=0.03 openedBackground=0.04 unexplained=0.05") })
        val report = result.debugReport()
        assertFalse(report.contains("evidence-"))
        assertFalse(report.contains("鲜色"))
        assertTrue(report.contains("逐格判断细节仅记入内部日志"))
        assertTrue(report.contains("未翻开 45格"))
    }

    @Test fun optionalPresenceEvidenceDoesNotInventLogMeasurements() {
        assertTrue(result().copy(boardCells = listOf(BoardCellObservation(0, 0, BoardCellState.CLOSED)))
            .cellDiagnosticLines().isEmpty())
    }
}
