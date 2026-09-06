package com.bagridmaster.app.ui

import org.junit.Assert.*
import org.junit.Test

class ImageZoomStateTest {
    @Test fun doubleTapKeepsTappedPointStationaryWhileZooming() {
        val zoom = ImageZoomState().transform(100f, -50f, 3f, 0f, 0f, 400f, 300f, 400f, 300f)
        assertEquals(3f, zoom.scale)
        assertEquals(-200f, zoom.x)
        assertEquals(100f, zoom.y)
        assertEquals(100f, 100f * zoom.scale + zoom.x)
    }

    @Test fun pinchAroundMovingCentroidAndResetAtMinimum() {
        val zoom = ImageZoomState(2f, -50f, 10f).transform(100f, 0f, 1.5f, 5f, -10f, 400f, 300f, 400f, 300f)
        assertEquals(ImageZoomState(3f, -120f, 5f), zoom)
        assertEquals(ImageZoomState(), zoom.transform(0f, 0f, .1f, 100f, 100f, 400f, 300f, 400f, 300f))
    }

    @Test fun clampsPanToImageEdgesAndCapsZoom() {
        val zoom = ImageZoomState().transform(0f, 0f, 100f, 9999f, -9999f, 400f, 200f, 400f, 600f)
        assertEquals(ImageZoomState(8f, 1400f, -500f), zoom)
    }

    @Test fun letterboxedAxisStaysCenteredUntilLargeEnough() {
        val zoom = ImageZoomState().transform(0f, 100f, 2f, 0f, 100f, 400f, 100f, 400f, 600f)
        assertEquals(0f, zoom.y)
    }
}
