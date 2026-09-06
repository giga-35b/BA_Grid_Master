package com.bagridmaster.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class PreRotationConventionTest {
    @Test fun directionlessAxisUsesShortestSignedCorrection() {
        assertEquals(-5, canonicalPreRotationDegrees(175))
        assertEquals(5, canonicalPreRotationDegrees(-175))
        assertEquals(90, canonicalPreRotationDegrees(90))
        assertEquals(90, canonicalPreRotationDegrees(-90))
        assertEquals(0, canonicalPreRotationDegrees(180))
    }
}
