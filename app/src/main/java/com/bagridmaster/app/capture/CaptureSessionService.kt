package com.bagridmaster.app.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bagridmaster.app.MainActivity
import com.bagridmaster.app.R
import com.bagridmaster.app.assistant.AssistantController

class CaptureSessionService : Service() {
    private var assistantRequestId = -1L
    private var projection: MediaProjection? = null
    private var frameStream: CaptureFrameStream? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projectionCallback: MediaProjection.Callback? = null

    private fun callbackFor(activeProjection: MediaProjection, requestId: Long) = object : MediaProjection.Callback() {
        override fun onStop() {
            if (projection !== activeProjection) return
            releaseFrameStream()
            projection = null
            CaptureRuntimeState.setActive(false)
            AssistantController.captureStopped(this@CaptureSessionService, requestId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (projection !== activeProjection || width <= 0 || height <= 0) return
            // Android 14+: these are the actual captured region dimensions, including app-only
            // sharing. Configuration/physical display bounds must not overwrite this callback.
            frameStream?.resize(CaptureSize(width, height, resources.configuration.densityDpi), "captured-content")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        assistantRequestId = intent?.getLongExtra(EXTRA_REQUEST_ID, -1L) ?: -1L
        if (!AssistantController.acceptsCapture(assistantRequestId)) {
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching {
            val foregroundType = if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundType,
            )

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            val resultData = intent?.readProjectionIntent()

            check(resultCode == Activity.RESULT_OK && resultData != null) { "Missing screen capture consent" }

            val manager = getSystemService(MediaProjectionManager::class.java)
            releaseFrameStream()
            projectionCallback?.let { projection?.unregisterCallback(it) }
            projection?.stop()
            projection = null
            val newProjection = checkNotNull(manager.getMediaProjection(resultCode, resultData)) {
                "MediaProjection permission result did not contain a projection token"
            }
            val callback = callbackFor(newProjection, assistantRequestId)
            projectionCallback = callback
            newProjection.registerCallback(callback, mainHandler)
            projection = newProjection
            startFrameStream(newProjection)
        }.onFailure {
            Log.e("BAGridCapture", "Capture startup failed", it)
            CaptureRuntimeState.setActive(false)
            AssistantController.fail(this, assistantRequestId, "获取屏幕授权失败：无法建立屏幕捕获会话")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseFrameStream()
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projection?.stop()
        projection = null
        CaptureRuntimeState.setActive(false)
        AssistantController.captureStopped(this, assistantRequestId)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null || Build.VERSION.SDK_INT >= 34) return
        // Older Android versions do not have onCapturedContentResize.
        runCatching { frameStream?.resize(initialCaptureSize(), "configuration-legacy") }.onFailure {
            Log.e("BAGridCapture", "Capture resize failed", it)
            AssistantController.fail(this, assistantRequestId, "屏幕捕获中断，悬浮助手已停用")
        }
    }

    private fun startFrameStream(activeProjection: MediaProjection) {
        val requestId = assistantRequestId
        val initialSize = initialCaptureSize()
        frameStream = CaptureFrameStream(activeProjection,
            onStarted = {
                mainHandler.post {
                    if (projection === activeProjection && AssistantController.acceptsCapture(requestId)) {
                        CaptureRuntimeState.setActive(true)
                        AssistantController.captureReady(this, requestId)
                    }
                }
            },
            onFailure = { error ->
                mainHandler.post {
                    if (projection === activeProjection) {
                        Log.e("BAGridCapture", "Capture stream failed", error)
                        val message = if (CaptureRuntimeState.isActive.value) "屏幕捕获中断，悬浮助手已停用"
                            else "获取屏幕授权失败：无法建立屏幕捕获会话"
                        AssistantController.fail(this, requestId, message)
                        stopSelf()
                    }
                }
            },
        )
        frameStream?.resize(initialSize, "initial-display-bounds")
    }

    private fun initialCaptureSize(): CaptureSize {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.run {
                android.graphics.Point().also { getRealSize(it) }
            }.let { android.graphics.Rect(0, 0, it.x, it.y) }
        }
        return CaptureSize(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), resources.configuration.densityDpi)
    }

    private fun releaseFrameStream() {
        frameStream?.close()
        frameStream = null
        CaptureFrameBroker.fail("屏幕捕获已停止，请重新启动悬浮助手并授权")
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_grid)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.readProjectionIntent(): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        getParcelableExtra(EXTRA_RESULT_DATA)
    }

    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 2101
        private const val EXTRA_REQUEST_ID = "assistant_request_id"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent, requestId: Long) {
            val intent = Intent(context, CaptureSessionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureSessionService::class.java))
        }
    }
}
