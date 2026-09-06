package com.bagridmaster.app.overlay

import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.model.*
import org.junit.Assert.*
import org.junit.Test

class OverlayPresentationTest {
    private val board = BoardGeometry(ScreenRegion(100, 200, 1000, 700), 5, 9, .99)
    private fun result() = AnalysisResult(emptyList(), 123, ResultSource.LIVE,
        RecognitionGeometry(board, null, "CV"))
    private fun card(index: Int, rows: Int, columns: Int, count: Int?) = ItemCardRecognition(index,
        ScreenRegion(20 + index * 100, 720, 120 + index * 100, 830),
        ScreenRegion(25 + index * 100, 725, 115 + index * 100, 800), ScreenRegion(20, 800, 40, 830),
        ScreenRegion(80, 800, 120, 830),
        ItemShape(rows, columns, (0 until rows).flatMap { r -> (0 until columns).map { c -> ShapeCell(r, c) } }.toSet()),
        count, count == 0, .99, RgbaPatch(1, 1, ByteArray(4)))
    private fun rectangle(top: Int, left: Int, rows: Int, columns: Int) =
        (top until top + rows).flatMap { r -> (left until left + columns).map { c -> GridCell(r, c) } }.toSet()
    private fun obj(id: String, type: Int, top: Int, left: Int, rows: Int, columns: Int) =
        BoardObjectObservation(id, BoardObjectPhase.COMPLETED, rectangle(top, left, rows, columns), listOf(type), .9, "")

    @Test fun compactHasOnlyRecommendationsExplorationConfidenceAndTime() {
        val text = overlayResultText(result().copy(recommendations = listOf(
            CellRecommendation(0, 0, .5, "completion"), CellRecommendation(1, 1, .15, "preset", true)),
            timings = AnalysisTimings(boardMs = 40, placementMatchingMs = 30, completionMs = 3, explorationMs = 10)), OverlayContentMode.COMPACT)
        assertEquals("建议：A1 B2*\n探索置信度（估计）：B2* 85%\n识别：70 ms · 推荐：13 ms", text)
        assertFalse(text.contains("棋盘"))
    }

    @Test fun noExplorationDoesNotShowCompletionScoreAsConfidence() {
        val text = overlayResultText(result().copy(recommendations = listOf(CellRecommendation(0, 0, .2, "补全"))), OverlayContentMode.COMPACT)
        assertFalse(text.contains("置信度"))
        assertFalse(text.contains("*"))
        assertEquals("", overlayResultText(result(), OverlayContentMode.BUTTON_ONLY))
    }

    @Test fun unknownOrInvalidProbabilityIsNotDisplayed() {
        val text = overlayResultText(result().copy(recommendations = listOf(CellRecommendation(0, 0, Double.NaN, "探索", true))), OverlayContentMode.COMPACT)
        assertTrue(text.contains("A1*"))
        assertFalse(text.contains("置信度"))
    }

    @Test fun allFiveCatalogsHaveCorrectInitialTotals() {
        InventoryConfiguration.entries.forEach { configuration ->
            val cards = configuration.items.mapIndexed { i, item -> card(i, item.rows, item.columns, item.count) }
            val shown = result().copy(inventory = cards).inventoryDisplay()
            assertEquals(configuration.items.map { it.count }, shown.map { it.total })
            assertEquals(listOf("a", "b", "c"), shown.map { itemCode(it.index) })
        }
    }

    @Test fun countsAreRemainingOverTotalNotRemainingOverRemaining() {
        val cards = listOf(card(0, 1, 3, 2), card(1, 1, 4, 1), card(2, 2, 3, 0))
        assertEquals("a：3×1，2/4", result().copy(inventory = cards).inventoryDisplay().first().label)
        assertEquals("c：3×2，0/1", result().copy(inventory = cards).inventoryDisplay().last().label)
    }

    @Test fun incompleteAmbiguousInventoryDoesNotGuessTotal() {
        val shown = result().copy(inventory = listOf(card(0, 1, 3, 1))).inventoryDisplay().single()
        assertNull(shown.total)
        assertEquals("a：3×1，1/?", shown.label)
    }

    @Test fun finishedMissingShapeCanStillUseUnambiguousCatalogCount() {
        val cards = listOf(card(0, 1, 2, 2), card(1, 2, 2, 3), card(2, 2, 4, 0).copy(shape = null))
        val shown = result().copy(inventory = cards).inventoryDisplay().last()
        assertEquals("c：形状?，0/1", shown.label)
    }

    @Test fun objectNumbersAreSortedWithinTypeRegardlessOfDiscoveryOrPhase() {
        val first = obj("C99", 0, 0, 0, 1, 3)
        val second = obj("L1", 0, 1, 1, 1, 3).copy(phase = BoardObjectPhase.LIT,
            observedCells = setOf(GridCell(1, 2)), estimatedFootprint = rectangle(1, 1, 1, 3))
        val third = obj("C1", 1, 0, 5, 4, 1)
        val expected = "[a1 A1横]、[a2 B2横]、[b1 F1纵]"
        assertEquals(expected, recognizedObjectList(listOf(second, third, first)))
        assertEquals(expected, recognizedObjectList(listOf(first, second, third)))
    }

    @Test fun acceptedMatchShowsActualPredictedOriginNotObservedCell() {
        val item = obj("L1", 0, 1, 1, 1, 1).copy(phase = BoardObjectPhase.LIT,
            localMatch = LocalTemplateMatch(true, listOf(TemplatePlacementMatch(0, GridCell(0, 1), 3, 1, 0, .9, .9, .9)), .2, ""))
        assertEquals("[a1 B1纵]", recognizedObjectList(listOf(item)))
        assertEquals("[a1 左上? 已见B2]", recognizedObjectList(listOf(item.copy(localMatch = item.localMatch!!.copy(accepted = false)))))
    }

    @Test fun fullContainsVerticesAndInventoryAndObjects() {
        val text = overlayResultText(result().copy(inventory = listOf(card(0, 1, 3, 4)),
            boardObjects = listOf(obj("C1", 0, 0, 1, 3, 1))), OverlayContentMode.FULL)
        assertTrue(text.contains("(100,200)–(1000,700)"))
        assertTrue(text.contains("a：3×1"))
        assertTrue(text.contains("已识别清单：[a1 B1纵]"))
    }

    @Test fun inventoryAnnotationTransformIncludesScaleAndInsetOffset() {
        val scaled = board.copy(region = ScreenRegion(190, 390, 1990, 1390))
        val annotation = inventoryOverlayAnnotations(result().copy(inventory = listOf(card(0, 1, 3, 4))), scaled).single()
        assertEquals(ScreenRegion(30, 1590, 230, 1650), annotation.region)
        assertEquals("a：3×1，4/4", annotation.label)
    }

    @Test fun badgeNeverExitsViewportEvenAtBottomRight() {
        val rect = inventoryBadgeBounds(ScreenRegion(950, 499, 1050, 550), 200, 32, 1000, 500)
        assertEquals(ScreenRegion(800, 468, 1000, 500), rect)
        assertEquals(ScreenRegion(0, 0, 100, 50), inventoryBadgeBounds(ScreenRegion(-50, -10, 80, 10), 999, 90, 100, 50))
    }

    @Test fun legacyDebugModeMigratesToFullAndSizeClampsBothDirections() {
        assertEquals(OverlayContentMode.FULL, OverlayContentMode.fromSaved("DEBUG"))
        assertEquals(OverlayContentMode.COMPACT, OverlayContentMode.fromSaved("garbage"))
        assertEquals(3, OverlayContentMode.entries.size)
        assertEquals(30f, normalizedBubbleSize(5f))
        assertEquals(75f, normalizedBubbleSize(88f))
        assertEquals(40f, normalizedBubbleSize(Float.NaN))
        assertEquals(40f, AppSettings().bubbleSizeDp)
        assertEquals(0.60f, AppSettings().bubbleOpacity)
        assertEquals(OverlayContentMode.COMPACT, AppSettings().overlayContentMode)
        assertTrue(AppSettings().showInventoryInfo)
        assertEquals(2f, AppSettings().markerStrokeDp)
        assertEquals(5, AppSettings().temporaryHideSeconds)
        assertEquals(1, normalizedTemporaryHideSeconds(-1))
        assertEquals(10, normalizedTemporaryHideSeconds(99))
    }
}
