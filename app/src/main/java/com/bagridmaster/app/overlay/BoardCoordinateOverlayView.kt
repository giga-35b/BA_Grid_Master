package com.bagridmaster.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.CellRecommendation
import com.bagridmaster.app.analysis.BoardObjectObservation
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.analysis.cellAddress
import kotlin.math.min

/** A visual-only, Excel-style board coordinate layer. Touch pass-through is set by its window. */
@SuppressLint("ViewConstructor") // Runtime-only overlay; its geometry is required at construction.
class BoardCoordinateOverlayView(
    context: Context,
    private val board: BoardGeometry,
    private val recommendations: List<CellRecommendation>,
    markerColor: Int,
    markerStrokeDp: Float,
    private val showHeaders: Boolean = true,
    private val showKnownObjects: Boolean = true,
    boardObjects: List<BoardObjectObservation> = emptyList(),
    boardCells: List<BoardCellObservation> = emptyList(),
    private val inventoryAnnotations: List<KnownObjectAnnotation> = emptyList(),
    private val viewportWidthPx: Int = Int.MAX_VALUE,
    private val viewportHeightPx: Int = Int.MAX_VALUE,
    densityOverride: Float? = null,
) : View(context) {
    private val objectAnnotations = knownObjectAnnotations(board, boardObjects, boardCells)
    private val density = densityOverride ?: resources.displayMetrics.density
    private val accentColor = markerColor
    private val strongStroke = (markerStrokeDp * density).coerceAtLeast(dp(2f))
    private val gridStroke = dp(1f)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor.withAlpha(72)
        style = Paint.Style.STROKE
        strokeWidth = gridStroke
    }
    private val recommendationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = strongStroke
    }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 24, 29, 33)
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val suggestionTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 8, 12, 14)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val suggestionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }

    init {
        contentDescription = if (recommendations.isEmpty()) {
            "棋盘坐标覆盖层"
        } else {
            "棋盘坐标覆盖层，建议 " + recommendations.joinToString { cellAddress(it.row, it.column) }
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (board.rows <= 0 || board.columns <= 0 || board.region.width <= 0 || board.region.height <= 0) return

        if (showHeaders) {
            drawGrid(canvas)
            drawHeaders(canvas)
        }
        if (showKnownObjects) drawObjects(canvas)
        recommendations.forEach { drawRecommendation(canvas, it) }
        drawInventory(canvas)
    }

    private fun drawInventory(canvas: Canvas) {
        val visibleWidth = min(width, viewportWidthPx)
        val visibleHeight = min(height, viewportHeightPx)
        if (visibleWidth <= 0 || visibleHeight <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        for (annotation in inventoryAnnotations) {
            paint.color = annotation.color
            paint.textSize = min(dp(10f), annotation.region.width * 0.09f)
            val padding = dp(3f)
            val room = (min(annotation.region.width, visibleWidth) - padding * 2).coerceAtLeast(1f)
            val measured = paint.measureText(annotation.label)
            if (measured > room) paint.textSize *= room / measured
            val bounds = inventoryBadgeBounds(annotation.region,
                kotlin.math.ceil(paint.measureText(annotation.label) + padding * 2).toInt(),
                kotlin.math.ceil(paint.descent() - paint.ascent() + padding * 2).toInt(), visibleWidth, visibleHeight)
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), badgeFillPaint)
            canvas.drawText(annotation.label, bounds.left + padding, bounds.top + padding - paint.ascent(), paint)
        }
    }

    private fun drawObjects(canvas: Canvas) {
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = min(dp(10f), board.region.height.toFloat() / board.rows * 0.22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for (annotation in objectAnnotations) {
            outline.color = annotation.color
            label.color = annotation.color
            val region = annotation.region
            canvas.drawRect(region.left + dp(3f), region.top + dp(3f), region.right - dp(3f), region.bottom - dp(3f), outline)
            val text = annotation.label
            if (text.isEmpty()) continue
            val baseline = region.bottom - dp(5f)
            canvas.drawRect(region.left + dp(4f), baseline - label.textSize, region.left + dp(6f) + label.measureText(text), baseline + dp(2f), badgeFillPaint)
            canvas.drawText(text, region.left + dp(5f), baseline, label)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val region = board.region
        val cellWidth = region.width.toFloat() / board.columns
        val cellHeight = region.height.toFloat() / board.rows
        val bounds = RectF(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat())
        canvas.drawRoundRect(bounds, dp(6f), dp(6f), gridPaint)

        for (column in 1 until board.columns) {
            val x = region.left + column * cellWidth
            canvas.drawLine(x, region.top.toFloat(), x, region.bottom.toFloat(), gridPaint)
        }
        for (row in 1 until board.rows) {
            val y = region.top + row * cellHeight
            canvas.drawLine(region.left.toFloat(), y, region.right.toFloat(), y, gridPaint)
        }
    }

    private fun drawHeaders(canvas: Canvas) {
        val region = board.region
        val cellWidth = region.width.toFloat() / board.columns
        val cellHeight = region.height.toFloat() / board.rows
        val badgeSize = min(dp(26f), min(cellWidth * 0.52f, cellHeight * 0.62f)).coerceAtLeast(dp(18f))
        val gap = dp(4f)
        val columnTop = (region.top - gap - badgeSize).coerceAtLeast(dp(2f))
        val rowLeft = (region.left - gap - badgeSize).coerceAtLeast(dp(2f))
        badgeTextPaint.textSize = badgeSize * 0.54f

        for (column in 0 until board.columns) {
            val centerX = region.left + (column + 0.5f) * cellWidth
            drawBadge(
                canvas = canvas,
                left = centerX - badgeSize / 2f,
                top = columnTop,
                size = badgeSize,
                text = cellAddress(0, column).dropLast(1),
            )
        }
        for (row in 0 until board.rows) {
            val centerY = region.top + (row + 0.5f) * cellHeight
            drawBadge(
                canvas = canvas,
                left = rowLeft,
                top = centerY - badgeSize / 2f,
                size = badgeSize,
                text = (row + 1).toString(),
            )
        }
    }

    private fun drawBadge(canvas: Canvas, left: Float, top: Float, size: Float, text: String) {
        val rect = RectF(left, top, left + size, top + size)
        val radius = size * 0.24f
        canvas.drawRoundRect(rect, radius, radius, badgeFillPaint)
        canvas.drawRoundRect(rect, radius, radius, badgeStrokePaint)
        val baseline = rect.centerY() - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
        canvas.drawText(text, rect.centerX(), baseline, badgeTextPaint)
    }

    private fun drawRecommendation(canvas: Canvas, recommendation: CellRecommendation) {
        val cell = board.cellRegion(recommendation.row, recommendation.column) ?: return
        val inset = strongStroke / 2f
        val rect = RectF(
            cell.left + inset,
            cell.top + inset,
            cell.right - inset,
            cell.bottom - inset,
        )
        canvas.drawRoundRect(rect, dp(7f), dp(7f), recommendationPaint)

        val label = recommendation.displayAddress()
        var textSize = min(dp(18f), cell.height * 0.30f).coerceAtLeast(dp(11f))
        suggestionTextPaint.textSize = textSize
        val maximumWidth = cell.width * 0.72f
        val measured = suggestionTextPaint.measureText(label)
        if (measured > maximumWidth) textSize *= maximumWidth / measured
        suggestionTextPaint.textSize = textSize
        suggestionTextOutlinePaint.textSize = textSize
        suggestionTextOutlinePaint.strokeWidth = maxOf(dp(2f), textSize * 0.18f)
        val baseline = rect.centerY() -
            (suggestionTextPaint.ascent() + suggestionTextPaint.descent()) / 2f
        canvas.drawText(label, rect.centerX(), baseline, suggestionTextOutlinePaint)
        canvas.drawText(label, rect.centerX(), baseline, suggestionTextPaint)
    }

    private fun dp(value: Float): Float = value * density

    private fun Int.withAlpha(alpha: Int): Int =
        (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
