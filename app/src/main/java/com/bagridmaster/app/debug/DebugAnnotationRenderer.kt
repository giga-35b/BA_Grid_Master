package com.bagridmaster.app.debug

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Shared by the on-screen preview and full-resolution PNG export. */
object DebugAnnotationRenderer {
    fun draw(canvas: Canvas, annotations: List<DebugAnnotation>, imageWidth: Int, imageHeight: Int) {
        val unit = imageWidth / 1280f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * unit }
        val background = Paint().apply { color = Color.argb(205, 12, 18, 24) }
        for (annotation in annotations) {
            stroke.color = annotation.color
            stroke.strokeWidth = (if (annotation.emphasis) 3.5f else 1f) * unit
            val region = annotation.region
            canvas.drawRect(region.left.toFloat(), region.top.toFloat(), region.right.toFloat(), region.bottom.toFloat(), stroke)
        }
        for (annotation in annotations) {
            val region = annotation.region
            text.color = annotation.color
            text.isFakeBoldText = annotation.emphasis
            val labelWidth = text.measureText(annotation.label)
            val x = (if (annotation.centerLabel) (region.left + region.right - labelWidth) / 2f else region.left.toFloat())
                .coerceIn(0f, (imageWidth - labelWidth).coerceAtLeast(0f))
            val requestedBaseline = when {
                annotation.aboveLabel -> region.top - 3f * unit
                annotation.centerLabel -> (region.top + region.bottom) / 2f - (text.ascent() + text.descent()) / 2f
                annotation.bottomLabel -> region.bottom - 3f * unit
                else -> region.top + text.textSize
            }
            val baseline = requestedBaseline.coerceIn(text.textSize, imageHeight - text.descent())
            canvas.drawRect(x, baseline + text.ascent(), x + labelWidth + 2f * unit, baseline + text.descent(), background)
            canvas.drawText(annotation.label, x + unit, baseline, text)
        }
    }
}
