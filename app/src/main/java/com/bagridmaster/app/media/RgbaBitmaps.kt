package com.bagridmaster.app.media

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.bagridmaster.app.vision.RgbaFrame

fun RgbaFrame.toBitmap(): Bitmap {
    val colors = IntArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val offset = y * rowStrideBytes + x * 4
        colors[y * width + x] = (0xFF shl 24) or ((rgba8888[offset].toInt() and 255) shl 16) or
            ((rgba8888[offset + 1].toInt() and 255) shl 8) or (rgba8888[offset + 2].toInt() and 255)
    }
    return createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(colors, 0, width, 0, 0, width, height)
    }
}

fun Bitmap.toRgbaFrame(): RgbaFrame {
    val colors = IntArray(width * height)
    getPixels(colors, 0, width, 0, 0, width, height)
    val rgba = ByteArray(colors.size * 4)
    for (index in colors.indices) {
        val color = colors[index]
        rgba[index * 4] = (color shr 16).toByte()
        rgba[index * 4 + 1] = (color shr 8).toByte()
        rgba[index * 4 + 2] = color.toByte()
        rgba[index * 4 + 3] = 255.toByte()
    }
    return RgbaFrame(width, height, width * 4, rgba, System.nanoTime())
}
