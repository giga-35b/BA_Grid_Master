package com.bagridmaster.app.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockAnalysisEngineTest {
    @Test
    fun returnsPrimaryAndOptionalAlternatives() = runBlocking {
        val result = MockAnalysisEngine().analyze(
            AnalysisRequest(
                captureDelayMs = 0,
                includeAlternativeCandidates = true,
                screenWidthPx = 1280,
                screenHeightPx = 576,
            ),
        )

        assertEquals(ResultSource.MOCK, result.source)
        assertEquals(3, result.recommendations.size)
        assertEquals(2, result.recommendations.first().row)
        assertEquals(6, result.recommendations.first().column)
        assertEquals(601, result.geometry.board.region.left)
        assertEquals(9, result.geometry.board.columns)
        assertEquals(5, result.geometry.board.rows)
        assertTrue(result.geometry.board.cellRegion(2, 6) != null)
        assertTrue(result.elapsedMs >= 0)
    }
}
