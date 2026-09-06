package com.bagridmaster.app.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.bagridmaster.app.vision.RgbaFrame
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryFrame(val frame: RgbaFrame, val description: String)

class LatestGalleryFrameSource(private val context: Context) {
    private fun checkAccess() {
        check(GalleryPermissions.hasFullAccess(context)) {
            if (GalleryPermissions.hasPartialAccess(context)) "仅获准访问部分照片，不能确定最新截图。请在配置页允许访问所有照片"
            else "相册图片权限未授予或已撤销，请在配置页授权"
        }
    }

    /** Snapshot all currently queryable IDs, without our normal size/export filters. */
    suspend fun latestImageId(): Long = withContext(Dispatchers.IO) {
        checkAccess()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            ?: error("无法检查相册最新图片，请确认权限后重试清屏")
    }

    suspend fun read(boundary: GalleryCaptureBoundary): GalleryFrame = withContext(Dispatchers.IO) {
        checkAccess()
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = buildList {
            add("${MediaStore.Images.Media.SIZE} > 0")
            add("(${MediaStore.Images.Media.DISPLAY_NAME} IS NULL OR ${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ?)")
            if (Build.VERSION.SDK_INT >= 29) add("${MediaStore.Images.Media.IS_PENDING} = 0")
            if (Build.VERSION.SDK_INT >= 30) add("${MediaStore.Images.Media.IS_TRASHED} = 0")
        }.joinToString(" AND ")
        val metadata = resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN),
            selection, arrayOf("$DEBUG_IMAGE_PREFIX%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                boundary.validate(cursor.getLong(0), cursor.getLong(2) * 1000, cursor.getLong(3))
                Triple(ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getString(1) ?: "未命名图片", cursor.getLong(2) * 1000)
            }
        } ?: error("相册中没有可读取图片，请截图并等待系统保存完成后重试")
        // Decode exactly this newest image. Never silently fall back to an older successful board.
        val bitmap = try { decodeContentImage(context, metadata.first) } catch (error: Exception) {
            throw IllegalStateException("最新图片「${metadata.second}」无法读取，请等待保存完成或重新截图：${error.message}", error)
        }
        val frame = try { bitmap.toRgbaFrame() } finally { bitmap.recycle() }
        val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(metadata.third))
        GalleryFrame(frame, "相册：${metadata.second} · 入库 $time · $GALLERY_REMINDER")
    }

}

/** A single user-approved document URI needs no broad gallery permission. */
class SelectedGalleryFrameSource(private val context: Context) {
    suspend fun read(uriText: String): GalleryFrame = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriText)
        val bitmap = try { decodeContentImage(context, uri) } catch (error: Exception) {
            throw IllegalStateException("所选图片无法读取，请重新选择：${error.message}", error)
        }
        val frame = try { bitmap.toRgbaFrame() } finally { bitmap.recycle() }
        GalleryFrame(frame, "自行选择：${displayName(uri)}")
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: "所选图片"
}

private fun decodeContentImage(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // ImageDecoder normalizes EXIF orientation. Bound decode memory for long photos.
            val factor = maxOf(1.0, kotlin.math.sqrt(info.size.width.toDouble() * info.size.height / MAX_IMAGE_PIXELS))
            decoder.setTargetSize((info.size.width / factor).toInt().coerceAtLeast(1), (info.size.height / factor).toInt().coerceAtLeast(1))
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片无法解码" }
    var sample = 1
    while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > MAX_IMAGE_PIXELS) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("图片无法读取")
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

private const val MAX_IMAGE_PIXELS = 8_000_000L
