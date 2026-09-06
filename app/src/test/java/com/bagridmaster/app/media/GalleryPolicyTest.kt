package com.bagridmaster.app.media

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.SubpixelGridGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPolicyTest {
    @Test fun proportionalScreenshotMapsToDisplayWithoutChangingGrid() {
        val board = BoardGeometry(
            ScreenRegion(570, 130, 1090, 420), 5, 9, 0.99,
            SubpixelGridGeometry(570.25, 130.5, 520.0 / 9.0, 290.0 / 5.0),
        )
        val mapped = board.mapGalleryToScreen(1200, 540, 2400, 1080)
        assertEquals(ScreenRegion(1140, 260, 2180, 840), mapped.region)
        assertEquals(ScreenRegion(1141, 261, 1256, 377), mapped.cellRegion(0, 0))
        assertEquals(ScreenRegion(2065, 725, 2181, 841), mapped.cellRegion(4, 8))
        assertEquals(5, mapped.rows)
        assertEquals(9, mapped.columns)
        assertEquals(ScreenRegion(570, 130, 1090, 420), board.region)
    }

    @Test(expected = IllegalArgumentException::class)
    fun portraitImageCannotBePastedOnLandscapeScreen() { validateGalleryAspect(1080, 2400, 2400, 1080) }

    @Test(expected = IllegalArgumentException::class)
    fun longScreenshotCannotBePastedOnScreen() { validateGalleryAspect(2400, 4000, 2400, 1080) }

    @Test(expected = IllegalArgumentException::class)
    fun croppedScreenshotCannotBeStretchedToScreen() { validateGalleryAspect(2200, 1080, 2400, 1080) }

    @Test fun debugExportsAreIdentifiableAndNormalScreenshotsRemainEligible() {
        assertTrue(isDebugExport("${DEBUG_IMAGE_PREFIX}20260830-original-abcd.png"))
        assertTrue(isDebugExport("${DEBUG_IMAGE_PREFIX}20260830-marked-abcd.png"))
        assertFalse(isDebugExport("Screenshot_20260830.png"))
        assertFalse(isDebugExport(null))
    }
}
