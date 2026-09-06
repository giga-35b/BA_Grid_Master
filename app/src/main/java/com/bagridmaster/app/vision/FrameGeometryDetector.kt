package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.RecognitionGeometry

/**
 * 与 Android Bitmap 解耦的 CV 输入。首版实现可把 MediaProjection 的 RGBA_8888 帧直接传入，
 * 后续也可以在 JNI/OpenCV 层消费同一块数据。
 */
data class RgbaFrame(
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val rgba8888: ByteArray,
    val timestampNanos: Long,
)

/** 从当前帧定位棋盘和物品托盘；求解算法只消费返回的归一化几何结果。 */
fun interface FrameGeometryDetector {
    suspend fun detect(frame: RgbaFrame): RecognitionGeometry?
}
