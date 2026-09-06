package com.bagridmaster.app.capture

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.bagridmaster.app.vision.RgbaFrame

/** All ImageReader and VirtualDisplay work, including release, lives on one capture thread. */
internal class CaptureFrameStream(
    private val projection: MediaProjection,
    onStarted: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("ba-grid-capture").also { it.start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var readerToken: CaptureSurfaceToken? = null

    private val coordinator = CaptureSurfaceCoordinator(
        scheduler = object : CaptureScheduler {
            override val nowMs: Long get() = SystemClock.uptimeMillis()
            override fun post(delayMs: Long, action: () -> Unit) { handler.postDelayed(action, delayMs) }
        },
        backend = object : CaptureSurfaceBackend {
            override fun prepareReader(token: CaptureSurfaceToken) {
                closeReader()
                readerToken = token
                reader = ImageReader.newInstance(token.size.width, token.size.height, PixelFormat.RGBA_8888, 2)
                    .also { it.setOnImageAvailableListener({ source -> readLatest(source, token) }, handler) }
                Log.i(TAG, "prepare generation=${token.generation} size=${token.size} recovery=${token.recoveries}")
            }

            override fun createDisplay(size: CaptureSize) {
                display = checkNotNull(projection.createVirtualDisplay(
                    "BA Grid Capture", size.width, size.height, size.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    checkNotNull(reader).surface, null, handler,
                )) { "Unable to create capture display" }
                Log.i(TAG, "created display=${display?.display?.displayId} size=$size")
            }

            override fun detach() {
                checkNotNull(display).surface = null
                closeReader()
                Log.i(TAG, "detached old capture Surface")
            }

            override fun resize(size: CaptureSize) {
                checkNotNull(display).resize(size.width, size.height, size.densityDpi)
                Log.i(TAG, "resized detached display to $size")
            }

            override fun attach() {
                checkNotNull(display).surface = checkNotNull(reader).surface
                Log.i(TAG, "attached generation=${readerToken?.generation} size=${readerToken?.size}")
            }

            override fun drain() {
                val source = reader ?: return
                readLatest(source, readerToken ?: return)
            }

            override fun release() {
                try {
                    runCatching { display?.release() }.onFailure { Log.w(TAG, "Display release failed", it) }
                    display = null
                    runCatching { closeReader() }.onFailure { Log.w(TAG, "Reader release failed", it) }
                } finally {
                    handler.removeCallbacksAndMessages(null)
                    thread.quitSafely()
                    Log.i(TAG, "capture stream released")
                }
            }
        },
        onStarted = onStarted,
        onFailure = onFailure,
    )

    fun resize(size: CaptureSize, reason: String) {
        if (coordinator.resize(size)) Log.i(TAG, "requested resize reason=$reason size=$size")
    }

    fun close() = coordinator.close()

    private fun closeReader() {
        val oldReader = reader
        reader = null
        readerToken = null
        try {
            oldReader?.setOnImageAvailableListener(null, null)
        } finally {
            oldReader?.close()
        }
    }

    private fun readLatest(source: ImageReader, token: CaptureSurfaceToken) {
        // Stale callbacks may still be queued after their reader has been closed.
        if (source !== reader || !coordinator.isCurrent(token)) return
        if (!coordinator.canRead(token)) return // A scheduled drain ends the warm-up even on static screens.
        val ticket = CaptureFrameBroker.currentTicket()
        val image = runCatching { source.acquireLatestImage() }.getOrElse { error ->
            if (ticket != null) coordinator.deliverIfCurrent(token) {
                CaptureFrameBroker.fail(ticket, "截图帧读取失败：${error.message}")
            }
            return
        } ?: return
        image.use {
            if (ticket == null) return
            runCatching {
                if (it.width != token.size.width || it.height != token.size.height) {
                    reject(token, ticket, "截图帧尺寸 ${it.width}×${it.height} 与捕获目标 ${token.size.width}×${token.size.height} 不一致")
                    return
                }
                val frame = copyFrame(it)
                val problem = CaptureFrameQuality.problem(frame)
                if (problem != null) {
                    reject(token, ticket, problem, frame)
                    return
                }
                coordinator.deliverIfCurrent(token) {
                    if (CaptureFrameBroker.offer(ticket, frame)) {
                        Log.i(TAG, "frame delivered generation=${token.generation} size=${frame.width}x${frame.height} " +
                            "planeRowStride=${it.planes[0].rowStride} timestamp=${frame.timestampNanos}")
                    }
                }
            }.onFailure { error ->
                coordinator.deliverIfCurrent(token) {
                    CaptureFrameBroker.fail(ticket, "截图帧读取失败：${error.message}")
                }
            }
        }
    }

    private fun reject(token: CaptureSurfaceToken, ticket: CaptureFrameBroker.Ticket, problem: String, frame: RgbaFrame? = null) {
        coordinator.deliverIfCurrent(token) {
            if (CaptureFrameBroker.currentTicket() !== ticket) return@deliverIfCurrent
            Log.w(TAG, "frame rejected generation=${token.generation} size=${token.size}: $problem")
            CaptureFrameBroker.note(ticket, "$problem；正在重新同步屏幕捕获", frame)
            if (!coordinator.recover(token)) {
                CaptureFrameBroker.fail(ticket, "$problem；重新同步后仍异常，请关闭并重新启动悬浮助手、重新授权屏幕捕获，或改用相册最新截图", frame)
            }
        }
    }

    private fun copyFrame(image: Image): RgbaFrame {
        val plane = image.planes.firstOrNull() ?: error("截图没有 RGBA plane")
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        check(pixelStride >= 4) { "不支持的 RGBA pixelStride=$pixelStride" }
        val source = plane.buffer
        val outputStride = width * 4
        val output = ByteArray(outputStride * height)
        if (pixelStride == 4) {
            for (row in 0 until height) {
                source.position(row * plane.rowStride)
                source.get(output, row * outputStride, outputStride)
            }
        } else {
            for (row in 0 until height) for (column in 0 until width) {
                val sourceOffset = row * plane.rowStride + column * pixelStride
                val targetOffset = row * outputStride + column * 4
                for (channel in 0..3) output[targetOffset + channel] = source.get(sourceOffset + channel)
            }
        }
        return RgbaFrame(width, height, outputStride, output, image.timestamp)
    }

    companion object { private const val TAG = "BAGridCapture" }
}
