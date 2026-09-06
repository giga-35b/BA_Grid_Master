package com.bagridmaster.app.overlay

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.ScreenRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class GameOverlayImageRendererTest {
    @Test fun densityTracksBoardCellPixelsInsteadOfSourceResolution() {
        val ordinary = board(cellPx = 63)
        val highResolution = board(cellPx = 210)

        assertEquals(1.5f, imageOverlayDensity(ordinary), 0.001f)
        assertEquals(5f, imageOverlayDensity(highResolution), 0.001f)
    }

    @Test fun densityIsBoundedForInvalidOrExtremeGeometry() {
        assertEquals(1f, imageOverlayDensity(BoardGeometry(ScreenRegion(0, 0, 10, 10), 0, 0, 0.0)), 0f)
        assertEquals(0.5f, imageOverlayDensity(board(cellPx = 10)), 0f)
        assertEquals(12f, imageOverlayDensity(board(cellPx = 600)), 0f)
    }

    private fun board(cellPx: Int) = BoardGeometry(
        region = ScreenRegion(100, 200, 100 + cellPx * 9, 200 + cellPx * 5),
        rows = 5,
        columns = 9,
        confidence = 1.0,
    )
}
