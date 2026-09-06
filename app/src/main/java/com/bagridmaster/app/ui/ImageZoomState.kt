package com.bagridmaster.app.ui

/** Coordinates are relative to the viewport center, where the fitted image is rendered. */
data class ImageZoomState(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun transform(anchorX: Float, anchorY: Float, zoom: Float, panX: Float, panY: Float,
        imageWidth: Float, imageHeight: Float, viewportWidth: Float, viewportHeight: Float): ImageZoomState {
        val next = (scale * zoom).coerceIn(1f, 8f)
        if (next == 1f) return ImageZoomState()
        val ratio = next / scale
        val limitX = ((imageWidth * next - viewportWidth) / 2).coerceAtLeast(0f)
        val limitY = ((imageHeight * next - viewportHeight) / 2).coerceAtLeast(0f)
        return ImageZoomState(next,
            (anchorX + (x - anchorX) * ratio + panX).coerceIn(-limitX, limitX),
            (anchorY + (y - anchorY) * ratio + panY).coerceIn(-limitY, limitY))
    }
}
