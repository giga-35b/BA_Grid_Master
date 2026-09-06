package com.bagridmaster.app

import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.CellRecommendation
import com.bagridmaster.app.analysis.RecognitionGeometry
import com.bagridmaster.app.analysis.ResultSource
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.debug.DebugAnnotation
import com.bagridmaster.app.debug.DebugAnnotationRenderer
import com.bagridmaster.app.debug.DebugImageExporter
import com.bagridmaster.app.debug.RecognitionDebugSnapshot
import com.bagridmaster.app.media.toBitmap
import com.bagridmaster.app.media.toRgbaFrame
import com.bagridmaster.app.vision.RgbaFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class DebugImageRenderingTest {
    @Test fun exportsBothModesAtFrameResolutionWithoutOverwritingInput() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frame = RgbaFrame(1280, 720, 5120, ByteArray(1280 * 720 * 4) { index ->
            when (index % 4) { 0 -> 20; 1 -> 40; 2 -> 80; else -> 255 }.toByte()
        }, 1)
        val result = AnalysisResult(listOf(CellRecommendation(0, 0, 0.5, "test")), 10, ResultSource.LIVE,
            RecognitionGeometry(BoardGeometry(ScreenRegion(100, 100, 1000, 600), 5, 9, 1.0), null, "test"))
        val snapshot = RecognitionDebugSnapshot(1, frame, result, "test", "test")
        val created = mutableListOf<Uri>()
        try {
            for (annotated in listOf(false, true)) {
                val filename = DebugImageExporter.save(context, snapshot, annotated).substringAfterLast('\n')
                val uri = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(filename), null,
                )!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
                created += uri
                val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                try {
                    assertEquals(1280, decoded.width)
                    assertEquals(720, decoded.height)
                    if (annotated) assertNotEquals(0xFF142850.toInt(), decoded.getPixel(100, 150))
                    else assertEquals(0xFF142850.toInt(), decoded.getPixel(100, 150))
                } finally { decoded.recycle() }
            }
            assertEquals(20.toByte(), frame.rgba8888[0])
        } finally {
            // Only these two test-created exports are removed; no album/source images are touched.
            created.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test fun rawPixelsSurviveRoundTripAndAnnotationDoesNotMutateSource() {
        val bytes = ByteArray(1280 * 720 * 4) { index -> when (index % 4) { 0 -> 20; 1 -> 40; 2 -> 80; else -> 255 }.toByte() }
        val original = RgbaFrame(1280, 720, 1280 * 4, bytes, 1)
        val bitmap = original.toBitmap()
        try {
            assertTrue(bitmap.isMutable)
            assertArrayEquals(bytes, bitmap.toRgbaFrame().rgba8888)
            DebugAnnotationRenderer.draw(Canvas(bitmap), listOf(DebugAnnotation(ScreenRegion(100, 100, 200, 200), "A1", 0xFF00FF00.toInt(), emphasis = true)), 1280, 720)
            assertNotEquals(0xFF142850.toInt(), bitmap.getPixel(100, 150))
            assertEquals(0xFF142850.toInt(), bitmap.getPixel(500, 500))
            assertEquals(20.toByte(), bytes[0])
            assertEquals(1280, bitmap.width)
            assertEquals(720, bitmap.height)
        } finally { bitmap.recycle() }
    }
}
