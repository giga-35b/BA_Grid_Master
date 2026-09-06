package com.bagridmaster.app.debug

import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.BoardObjectObservation
import com.bagridmaster.app.analysis.BoardObjectPhase
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.CellRecommendation
import com.bagridmaster.app.analysis.RecognitionGeometry
import com.bagridmaster.app.analysis.ResultSource
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.LocalTemplateMatch
import com.bagridmaster.app.analysis.TemplatePlacementMatch
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.vision.BoardCellState
import com.bagridmaster.app.vision.RgbaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionDebugTest {
    private val result = AnalysisResult(
        recommendations = emptyList(), elapsedMs = 10, source = ResultSource.LIVE,
        geometry = RecognitionGeometry(BoardGeometry(ScreenRegion(100, 100, 1000, 600), 5, 9, 0.99), null, "test"),
        boardCells = listOf(BoardCellObservation(1, 2, BoardCellState.OPEN_FRAGMENT), BoardCellObservation(0, 0, BoardCellState.SELECTED)),
        boardObjects = listOf(BoardObjectObservation("L1", BoardObjectPhase.LIT, setOf(GridCell(1, 2)), emptyList(), 0.0, "未知类型")),
    )

    @Test
    fun annotationsUseCaptureCoordinatesAndOnlyObservedCells() {
        val annotations = result.debugAnnotations()
        val objectMarks = annotations.filter { it.label.startsWith("L1") }
        assertEquals(1, objectMarks.size)
        assertEquals(ScreenRegion(300, 200, 400, 300), objectMarks.single().region)
        assertTrue(objectMarks.single().label.contains("?"))
        assertTrue(annotations.any { it.label == "A1 S" })
    }

    @Test
    fun reportPreservesUncertainTypesAndSelectedState() {
        val text = result.debugReport()
        assertTrue(text.contains("种类未知"))
        assertTrue(text.contains("已勾选（按未翻开计算）"))
        assertTrue(text.contains("C2"))
        assertFalse(text.contains("物品a（推定）"))
    }

    @Test
    fun recommendationAnnotationIsTheWholeCellNotTheBottomHalf() {
        val annotated = result.copy(recommendations = listOf(CellRecommendation(1, 2, 0.25, "test"))).debugAnnotations()
        val mark = annotated.single { it.label == "建议 C2" }
        assertEquals(ScreenRegion(300, 200, 400, 300), mark.region)
        assertTrue(mark.centerLabel)
        assertTrue(mark.emphasis)
        assertTrue(annotated.first().aboveLabel)
    }

    @Test
    fun failedAttemptReplacesOldSuccessAndCanBeCleared() {
        val first = RgbaFrame(1, 1, 4, ByteArray(4), 1)
        val failedFrame = first.copy(timestampNanos = 2)
        try {
            RecognitionDebugStore.publish(RecognitionDebugSnapshot(1, first, result, "成功", "贪婪"))
            RecognitionDebugStore.publish(RecognitionDebugSnapshot(2, failedFrame, null, "未识别", "贪婪"))
            assertSame(failedFrame, RecognitionDebugStore.latest.value?.frame)
            assertNull(RecognitionDebugStore.latest.value?.result)
            RecognitionDebugStore.clear()
            assertNull(RecognitionDebugStore.latest.value)
        } finally { RecognitionDebugStore.clear() }
    }

    @Test fun reportSeparatesColorScoreFromMiddlePositionAndInferredFootprint() {
        val candidate = TemplatePlacementMatch(0, GridCell(0, 2), 3, 1, 303, 0.90, 0.96, 0.77)
        val enriched = result.copy(boardObjects = listOf(result.boardObjects.single().copy(
            possibleItemIndices = listOf(0), confidence = 0.99,
            localMatch = LocalTemplateMatch(true, listOf(candidate), 0.27, "test"),
            completionEvidence = "采用局部贴图对齐的推定占格")))
        val text = enriched.debugReport()
        assertTrue(text.contains("颜色匹配分（非位置概率）"))
        assertTrue(text.contains("C2 → 竖向中段 第2/3 格"))
        assertTrue(text.contains("推定占格：C1 C2 C3"))
        assertTrue(text.contains("不是游戏内的旋转角"))
        val marks = enriched.debugAnnotations().filter { it.label.startsWith("L1") }
        assertEquals("only observed C2 is marked as an object", 1, marks.size)
        assertTrue(marks.single().label.contains("2/3"))
    }

    @Test fun rejectedMatchIsOnlyACandidateAndNeverGetsSlotAnnotation() {
        val candidate = TemplatePlacementMatch(0, GridCell(0, 2), 3, 1, 300, 0.72, 0.90, 0.35)
        val enriched = result.copy(boardObjects = listOf(result.boardObjects.single().copy(
            localMatch = LocalTemplateMatch(false, listOf(candidate), 0.01, "纹理不足"))))
        assertTrue(enriched.debugReport().contains("局部贴图匹配：未确定"))
        assertTrue(enriched.debugReport().contains("候选1 物品a"))
        assertFalse(enriched.debugReport().contains("采用摆法"))
        assertFalse(enriched.debugAnnotations().filter { it.label.startsWith("L1") }.any { it.label.contains("2/3") })
    }
}
