package com.bagridmaster.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreeSideDockingTest {
    @Test
    fun `snaps to nearest of top left and right`() {
        val top = ThreeSideDocking.place(400, 12, 200, 100, 1000, 600, 8, true)
        val left = ThreeSideDocking.place(10, 300, 200, 100, 1000, 600, 8, true)
        val right = ThreeSideDocking.place(790, 300, 200, 100, 1000, 600, 8, true)

        assertEquals(DockSide.TOP, top.side)
        assertEquals(8, top.y)
        assertEquals(DockSide.LEFT, left.side)
        assertEquals(8, left.x)
        assertEquals(DockSide.RIGHT, right.side)
        assertEquals(792, right.x)
    }

    @Test
    fun `keeps a preferred side after window size changes`() {
        val result = ThreeSideDocking.place(
            x = 900,
            y = 250,
            windowWidth = 320,
            windowHeight = 120,
            screenWidth = 1000,
            screenHeight = 600,
            margin = 8,
            snapEnabled = true,
            preferredSide = DockSide.RIGHT,
        )

        assertEquals(DockSide.RIGHT, result.side)
        assertEquals(672, result.x)
    }
}
