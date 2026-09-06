package com.bagridmaster.app.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only provider regression checks: no photos are created, decoded, changed or deleted. */
@RunWith(AndroidJUnit4::class)
class LatestGalleryFrameSourceTest {
    @Test fun queriesImageWatermarkWithExistingPhotoPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GalleryPermissions.hasFullAccess(context))
        assertTrue(LatestGalleryFrameSource(context).latestImageId() >= 0)
    }

    @Test fun staleLatestImageIsRejectedBeforeDecoding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(GalleryPermissions.hasFullAccess(context))
        val source = LatestGalleryFrameSource(context)
        assumeTrue(source.latestImageId() > 0)
        // A future boundary makes this stable even if a screenshot is saved during the test.
        val error = runCatching {
            source.read(GalleryCaptureBoundary(Long.MAX_VALUE, System.currentTimeMillis()))
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("不会重复读取旧图片"))
    }
}
