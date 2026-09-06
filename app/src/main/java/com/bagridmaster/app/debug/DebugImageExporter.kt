package com.bagridmaster.app.debug

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.bagridmaster.app.media.DEBUG_IMAGE_PREFIX
import com.bagridmaster.app.media.toBitmap
import com.bagridmaster.app.model.AppSettings
import com.bagridmaster.app.overlay.GameOverlayImageRenderer
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DebugImageExporter {
    fun needsLegacyWritePermission(context: Context): Boolean = Build.VERSION.SDK_INT <= 28 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    suspend fun save(
        context: Context,
        snapshot: RecognitionDebugSnapshot,
        annotated: Boolean,
        quarterTurns: Int = 0,
        gameOverlaySettings: AppSettings? = null,
    ): String = withContext(Dispatchers.IO) {
        check(!needsLegacyWritePermission(context)) { "保存到相册需要存储写入权限" }
        val frame = checkNotNull(snapshot.frame) { "本次没有可保存的截图" }
        val source = frame.toBitmap()
        var bitmap = source
        val kind = if (annotated) "marked" else "original"
        val name = "$DEBUG_IMAGE_PREFIX${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}-$kind-${UUID.randomUUID().toString().take(8)}.png"
        try {
            if (annotated) {
                val result = snapshot.result
                if (gameOverlaySettings != null && result != null) {
                    withContext(Dispatchers.Main.immediate) {
                        GameOverlayImageRenderer.draw(context, source, result, gameOverlaySettings)
                    }
                } else {
                    DebugAnnotationRenderer.draw(Canvas(source), result?.debugAnnotations().orEmpty(), frame.width, frame.height)
                }
            }
            if (quarterTurns % 2 != 0) {
                bitmap = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                    Matrix().apply { postRotate(90f) }, true)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BA Grid Master/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建相册图片" }
                try {
                    checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败" } }
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0) { "图片未能发布到相册" }
                } catch (error: Exception) {
                    // Roll back only the new image this save action created, never the source.
                    runCatching { resolver.delete(uri, null, null) }
                    throw error
                }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BA Grid Master")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建保存目录" }
                val file = File(directory, name)
                check(file.createNewFile()) { "保存文件名冲突，请重试" }
                try {
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片保存失败" } }
                } catch (error: Exception) { file.delete(); throw error }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            }
        } finally {
            if (bitmap !== source) bitmap.recycle()
            source.recycle()
        }
        "已保存${if (annotated) "标注图" else "原图"}到相册 / BA Grid Master\n$name"
    }
}
