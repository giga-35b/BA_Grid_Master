package com.bagridmaster.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.model.AppSettings
import kotlin.math.min

/** Draws the same configurable game overlay directly in source-image coordinates. */
object GameOverlayImageRenderer {
    fun draw(context: Context, bitmap: Bitmap, result: AnalysisResult, settings: AppSettings) {
        require(bitmap.isMutable) { "Overlay destination must be mutable" }
        val board = result.geometry.board
        val view = BoardCoordinateOverlayView(
            context = context.applicationContext,
            board = board,
            recommendations = result.recommendations,
            markerColor = settings.markerColor.argb,
            markerStrokeDp = settings.markerStrokeDp,
            showHeaders = settings.showBoardHeaders,
            showKnownObjects = settings.showKnownObjects,
            boardObjects = result.boardObjects,
            boardCells = result.boardCells,
            inventoryAnnotations = if (settings.showInventoryInfo) {
                inventoryOverlayAnnotations(result, board)
            } else emptyList(),
            viewportWidthPx = bitmap.width,
            viewportHeightPx = bitmap.height,
            // Reconstruct the same roughly 45dp board-cell scale used by the live overlay.
            // A fixed density of 1 made every dp-based label only 10-26 source pixels, so
            // high-resolution selected photos became unreadable after being fitted onscreen.
            densityOverride = imageOverlayDensity(board),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(bitmap.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bitmap.height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, bitmap.width, bitmap.height)
        view.draw(Canvas(bitmap))
    }
}

internal fun imageOverlayDensity(board: BoardGeometry): Float {
    if (board.columns <= 0 || board.rows <= 0) return 1f
    val cellWidthPx = board.region.width.toFloat() / board.columns
    val cellHeightPx = board.region.height.toFloat() / board.rows
    return (min(cellWidthPx, cellHeightPx) / LIVE_OVERLAY_CELL_DP).coerceIn(0.5f, 12f)
}

private const val LIVE_OVERLAY_CELL_DP = 42f
