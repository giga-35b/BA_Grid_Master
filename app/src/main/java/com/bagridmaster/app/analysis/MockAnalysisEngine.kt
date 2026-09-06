package com.bagridmaster.app.analysis

import kotlinx.coroutines.delay

class MockAnalysisEngine : AnalysisEngine {
    override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        val startedAt = System.nanoTime()
        delay(request.captureDelayMs.toLong() + 180L)

        val primary = CellRecommendation(
            row = 2,
            column = 6,
            missProbability = 0.173,
            strategy = "Mock Greedy",
        )
        val alternatives = if (request.includeAlternativeCandidates) {
            listOf(
                CellRecommendation(1, 6, 0.181, "Mock 1-ply"),
                CellRecommendation(3, 7, 0.194, "Mock 1-ply"),
            )
        } else {
            emptyList()
        }

        val width = request.screenWidthPx.coerceAtLeast(1)
        val height = request.screenHeightPx.coerceAtLeast(1)
        val geometry = RecognitionGeometry(
            board = BoardGeometry(
                region = ScreenRegion(
                    left = (width * 0.47f).toInt(),
                    top = (height * 0.24f).toInt(),
                    right = (width * 0.91f).toInt(),
                    bottom = (height * 0.78f).toInt(),
                ),
                rows = 5,
                columns = 9,
                confidence = 0.86,
            ),
            itemTray = ScreenRegion(
                left = (width * 0.13f).toInt(),
                top = (height * 0.70f).toInt(),
                right = (width * 0.42f).toInt(),
                bottom = (height * 0.94f).toInt(),
            ),
            detectorLabel = "Mock 比例定位",
        )

        return AnalysisResult(
            recommendations = listOf(primary) + alternatives,
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            source = ResultSource.MOCK,
            geometry = geometry,
        )
    }
}
