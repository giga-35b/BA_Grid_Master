package com.bagridmaster.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.bagridmaster.app.MainActivity
import com.bagridmaster.app.R
import com.bagridmaster.app.assistant.AssistantController
import com.bagridmaster.app.analysis.AnalysisEngine
import com.bagridmaster.app.analysis.AnalysisRequest
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.CvAnalysisEngine
import com.bagridmaster.app.analysis.ResultSource
import com.bagridmaster.app.data.SettingsRepository
import com.bagridmaster.app.model.normalizedBubbleSize
import com.bagridmaster.app.model.AppSettings
import com.bagridmaster.app.model.OverlayContentMode
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.media.GALLERY_REMINDER
import com.bagridmaster.app.media.GalleryCaptureBoundary
import com.bagridmaster.app.media.LatestGalleryFrameSource
import com.bagridmaster.app.media.mapGalleryToScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private var assistantRequestId = -1L
    private lateinit var windowManager: WindowManager
    private lateinit var repository: SettingsRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var overlayView: FrameLayout? = null
    private var overlayContentView: LinearLayout? = null
    private var actionView: TextView? = null
    private var temporaryHideView: TextView? = null
    private var closeView: TextView? = null
    private var infoView: TextView? = null
    private var galleryNoticeView: TextView? = null
    private var markerView: View? = null
    private var resizeQueued = false
    private var overlayParams: WindowManager.LayoutParams? = null
    private var touchController: OverlayTouchController? = null
    private var settingsJob: Job? = null
    private var analysisJob: Job? = null
    private var initialPositionApplied = false
    private var currentDockSide = DockSide.RIGHT
    private var currentSettings = AppSettings()
    private var lastResult: AnalysisResult? = null
    private var statusMessage: String? = null
    private var isAnalyzing = false
    private val galleryWorkflow = GalleryCaptureWorkflow()
    private val galleryTiming = GalleryCaptureTiming()
    private var galleryDimJob: Job? = null
    private var temporaryHideJob: Job? = null
    private var galleryBoundaryTask: Deferred<GalleryCaptureBoundary>? = null
    private var operationGeneration = 0
    private lateinit var analysisEngine: AnalysisEngine

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        repository = SettingsRepository(this)
        analysisEngine = CvAnalysisEngine(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        assistantRequestId = intent?.getLongExtra(EXTRA_REQUEST_ID, -1L) ?: -1L
        if (!AssistantController.acceptsOverlay(assistantRequestId)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            AssistantController.fail(this, assistantRequestId, "悬浮助手启动失败：请先授予悬浮窗权限")
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching {
            currentSettings = currentSettings.copy(imageInputMode = AssistantController.status.value.mode)
            startAsForegroundService()
            if (overlayView == null) addOverlay()
            observeSettings()
            OverlayRuntimeState.setRunning(true)
            AssistantController.overlayReady(assistantRequestId)
        }.onFailure {
            Log.e("BAGridOverlay", "Overlay startup failed", it)
            AssistantController.fail(this, assistantRequestId, "悬浮助手启动失败：无法显示悬浮窗")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        operationGeneration++
        settingsJob?.cancel()
        analysisJob?.cancel()
        galleryDimJob?.cancel()
        temporaryHideJob?.cancel()
        galleryBoundaryTask?.cancel()
        touchController?.dispose()
        touchController = null
        removeViewSafely(markerView)
        removeViewSafely(overlayView)
        markerView = null
        overlayView = null
        overlayContentView = null
        actionView = null
        temporaryHideView = null
        closeView = null
        infoView = null
        galleryNoticeView = null
        overlayParams = null
        serviceScope.cancel()
        (analysisEngine as? AutoCloseable)?.close()
        OverlayRuntimeState.setRunning(false)
        AssistantController.overlayStopped(this, assistantRequestId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeSettings() {
        if (settingsJob != null) return
        settingsJob = serviceScope.launch {
            repository.settings.collectLatest { settings ->
                if (settings.imageInputMode != currentSettings.imageInputMode) {
                    operationGeneration++
                    analysisJob?.cancel()
                    galleryDimJob?.cancel()
                    temporaryHideJob?.cancel()
                    temporaryHideJob = null
                    galleryBoundaryTask?.cancel()
                    galleryBoundaryTask = null
                    galleryTiming.reset()
                    galleryWorkflow.reset()
                    isAnalyzing = false
                    lastResult = null
                    statusMessage = null
                    overlayView?.visibility = View.VISIBLE
                    removeMarker()
                }
                currentSettings = settings
                applyOverlaySettings(settings)
            }
        }
    }

    private fun addOverlay() {
        val root = FrameLayout(this).apply {
            elevation = dp(8f).toFloat()
            clipChildren = false
            clipToPadding = false
            contentDescription = "BA Grid Master 悬浮识别面板"
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val action = AccessibleActionView(this).apply {
            text = "◎"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            background = actionBackground()
            contentDescription = "触发棋盘识别"
        }
        val temporaryHide = AccessibleActionView(this).apply {
            text = "临时隐藏"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = temporaryHideBackground()
            contentDescription = "临时隐藏悬浮窗以便截图"
            visibility = View.GONE
            setOnClickListener { onTemporaryHideClick() }
        }
        val close = AccessibleActionView(this).apply {
            text = "×"
            gravity = Gravity.CENTER
            setTextColor(0xFFFF4D4D.toInt())
            textSize = CLOSE_BUTTON_TEXT_SIZE_SP
            includeFontPadding = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            elevation = dp(12f).toFloat()
            background = closeBackground()
            contentDescription = "停止悬浮助手"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                AssistantController.stop(applicationContext)
            }
        }
        val info = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(0f, 0.96f)
            isSingleLine = false
        }

        overlayView = root
        overlayContentView = content
        actionView = action
        temporaryHideView = temporaryHide
        closeView = close
        infoView = info
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(close)
        configureOverlayContent()

        val (width, height) = overlayDimensions(currentSettings)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX(width)
            y = initialY(height)
        }

        root.alpha = currentSettings.bubbleOpacity
        windowManager.addView(root, params)
        overlayParams = params
        touchController = OverlayTouchController(
            root = root,
            action = action,
            readPosition = { params.x to params.y },
            moveTo = { x, y ->
                params.x = x
                params.y = y
                runCatching { windowManager.updateViewLayout(root, params) }
            },
            onDragFinished = { snapAndPersist(params) },
            onClick = { onActionClick() },
            onLongClick = { clearPreviousOverlay() },
        )
    }

    private fun applyOverlaySettings(settings: AppSettings) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val previousSide = currentDockSide
        configureOverlayContent()
        val (width, height) = overlayDimensions(settings)
        params.width = width
        params.height = height
        view.alpha = galleryTiming.alpha(SystemClock.elapsedRealtime(), settings.bubbleOpacity)

        if (!initialPositionApplied) {
            if (settings.bubbleX >= 0 && settings.bubbleY >= 0) {
                params.x = settings.bubbleX
                params.y = settings.bubbleY
            }
            val placed = calculateDock(params, preferredSide = null)
            params.x = placed.x
            params.y = placed.y
            currentDockSide = placed.side
            initialPositionApplied = true
        } else {
            val preferred = currentDockSide.takeIf { settings.snapToEdge }
            val placed = calculateDock(params, preferred)
            params.x = placed.x
            params.y = placed.y
            currentDockSide = placed.side
        }

        if (previousSide != currentDockSide) configureOverlayContent()
        runCatching { windowManager.updateViewLayout(view, params) }
        if (!isAnalyzing) lastResult?.let { showBoardOverlay(it) }
    }

    private fun configureOverlayContent() {
        val root = overlayView ?: return
        val contentRoot = overlayContentView ?: return
        val action = actionView ?: return
        val temporaryHide = temporaryHideView ?: return
        val close = closeView ?: return
        val info = infoView ?: return
        val buttonSize = dp(normalizedBubbleSize(currentSettings.bubbleSizeDp))
        val galleryMode = currentSettings.imageInputMode == ImageInputMode.LATEST_PHOTO
        val actionTextSize = minOf(if (galleryMode) 15f else 22f,
            normalizedBubbleSize(currentSettings.bubbleSizeDp) * 0.4f)
        close.textSize = CLOSE_BUTTON_TEXT_SIZE_SP
        contentRoot.removeAllViews()
        galleryNoticeView = null
        (action.parent as? ViewGroup)?.removeView(action)
        (info.parent as? ViewGroup)?.removeView(info)
        (temporaryHide.parent as? ViewGroup)?.removeView(temporaryHide)
        contentRoot.gravity = if (currentSettings.overlayContentMode == OverlayContentMode.FULL) Gravity.TOP else Gravity.CENTER_VERTICAL
        action.layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        action.background = actionBackground()
        val controls = if (galleryMode) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(action)
                addView(temporaryHide, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4f) })
            }
        } else action

        if (currentSettings.overlayContentMode == OverlayContentMode.BUTTON_ONLY && !galleryMode) {
            contentRoot.setPadding(0, 0, 0, 0)
            contentRoot.background = Color.TRANSPARENT.toDrawable()
            contentRoot.addView(action)
        } else {
            val padding = dp(5f)
            contentRoot.setPadding(padding, padding, padding, padding)
            contentRoot.background = panelBackground()
            info.textSize = if (currentSettings.overlayContentMode == OverlayContentMode.FULL) 10.5f else 12f
            info.maxLines = Int.MAX_VALUE
            info.setPadding(dp(8f), 0, dp(8f), 0)
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (galleryMode) {
                    val notice = TextView(this@OverlayService).apply {
                        textSize = 9.5f
                        setTextColor(0xFFFFD580.toInt())
                        setPadding(dp(8f), 0, dp(6f), dp(3f))
                    }
                    galleryNoticeView = notice
                    addView(notice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                if (currentSettings.overlayContentMode != OverlayContentMode.BUTTON_ONLY) {
                    addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            val content: View = if (currentSettings.overlayContentMode == OverlayContentMode.FULL) {
                ContentScrollView(this).apply {
                    isFillViewport = false
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(details)
                }
            } else details
            if (currentDockSide == DockSide.RIGHT) {
                contentRoot.addView(content)
                contentRoot.addView(controls)
            } else {
                contentRoot.addView(controls)
                contentRoot.addView(content)
            }
        }
        val closeDiameter = kotlin.math.ceil(close.paint.measureText(close.text.toString()) * 2f)
            .toInt()
            .coerceAtLeast(1)
        close.layoutParams = FrameLayout.LayoutParams(closeDiameter, closeDiameter, Gravity.END or Gravity.BOTTOM)
        close.bringToFront()
        renderOverlayContent()
    }

    private fun renderOverlayContent() {
        renderOverlayText()
        scheduleContentResize()
    }

    @SuppressLint("SetTextI18n") // Pixel diagnostics are intentionally developer-facing in v0.1.
    private fun renderOverlayText() {
        val galleryMode = currentSettings.imageInputMode == ImageInputMode.LATEST_PHOTO
        val clearing = galleryWorkflow.phase == GalleryCaptureWorkflow.Phase.CLEARING
        val ready = galleryWorkflow.phase == GalleryCaptureWorkflow.Phase.READY
        val actionTextSize = minOf(if (galleryMode) 15f else 22f, normalizedBubbleSize(currentSettings.bubbleSizeDp) * 0.4f)
        actionView?.textSize = actionTextSize
        closeView?.textSize = CLOSE_BUTTON_TEXT_SIZE_SP
        temporaryHideView?.apply {
            textSize = actionTextSize
            visibility = if (galleryMode && ready && !isAnalyzing) View.VISIBLE else View.GONE
            isEnabled = galleryMode && ready && !isAnalyzing
        }
        actionView?.isEnabled = !isAnalyzing && !clearing
        actionView?.contentDescription = when {
            isAnalyzing -> "正在识别"
            galleryMode && clearing -> "正在清屏准备截图"
            galleryMode && !ready -> "清除覆盖，面板降至百分之十不透明度三秒，半秒后可识别"
            galleryMode -> "识别清屏后的新截图；无需等待三秒结束，长按可重新清屏"
            else -> "触发棋盘识别；长按清除覆盖"
        }
        actionView?.text = when {
            isAnalyzing -> "…"
            galleryMode && clearing -> "…"
            galleryMode -> if (ready) "识别" else "清屏"
            statusMessage != null -> "!"
            lastResult != null -> "✓"
            else -> "◎"
        }
        galleryNoticeView?.text = if (currentSettings.overlayContentMode == OverlayContentMode.BUTTON_ONLY) {
            GALLERY_REMINDER + "\n" + when {
                isAnalyzing -> "正在识别…"
                statusMessage != null -> statusMessage
                clearing -> "清屏中，0.5秒后可识别"
                ready -> "保存新截图后点识别"
                else -> "清屏 → 截图 → 识别"
            }
        } else GALLERY_REMINDER

        val info = infoView ?: return
        if (currentSettings.overlayContentMode == OverlayContentMode.BUTTON_ONLY) return
        if (isAnalyzing) {
            info.text = "正在识别…\n请保持棋盘画面稳定"
            return
        }
        statusMessage?.let {
            info.text = "${if (galleryMode) "操作提示" else "未识别到内容"}\n$it"
            return
        }

        val result = lastResult
        if (result == null) {
            info.text = if (galleryMode) {
                if (ready) "等待新的游戏截图\n旧覆盖不会自动恢复"
                else "准备新一轮识别\n请先点击清屏按钮"
            } else if (currentSettings.overlayContentMode == OverlayContentMode.FULL) {
                "状态：等待识别\n棋盘：--\n物品：--\n建议：--\n点击按钮开始"
            } else {
                "等待识别 · 点击按钮\n棋盘 --  ·  物品 --"
            }
            return
        }

        info.text = overlayResultText(result, currentSettings.overlayContentMode)
    }

    private fun onActionClick() {
        if (isAnalyzing || galleryWorkflow.phase == GalleryCaptureWorkflow.Phase.CLEARING) return
        if (currentSettings.imageInputMode == ImageInputMode.LATEST_PHOTO &&
            galleryWorkflow.phase != GalleryCaptureWorkflow.Phase.READY) {
            prepareGalleryScreenshot()
        } else simulateAnalysis()
    }

    private fun onTemporaryHideClick() {
        val overlay = overlayView ?: return
        if (currentSettings.imageInputMode != ImageInputMode.LATEST_PHOTO ||
            galleryWorkflow.phase != GalleryCaptureWorkflow.Phase.READY || isAnalyzing) return
        temporaryHideJob?.cancel()
        temporaryHideJob = serviceScope.launch {
            val source = LatestGalleryFrameSource(applicationContext)
            val previousMaxId = try {
                source.latestImageId()
            } catch (error: Exception) {
                statusMessage = error.message ?: "无法检查相册最新图片"
                renderOverlayContent()
                return@launch
            }
            if (overlayView !== overlay || currentSettings.imageInputMode != ImageInputMode.LATEST_PHOTO) return@launch
            if (currentSettings.hapticsEnabled) {
                overlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            galleryDimJob?.cancel()
            galleryTiming.reset()
            removeMarker()
            overlay.visibility = View.INVISIBLE
            val timing = TemporaryHideTiming(SystemClock.elapsedRealtime(), currentSettings.temporaryHideSeconds)
            try {
                while (true) {
                    val remainingMs = timing.remainingMs(SystemClock.elapsedRealtime())
                    if (remainingMs > 0) delay(minOf(150L, remainingMs))
                    val currentMaxId = runCatching { source.latestImageId() }.getOrNull()
                    if (timing.shouldRestore(SystemClock.elapsedRealtime(), previousMaxId, currentMaxId)) break
                }
            } finally {
                if (overlayView === overlay) {
                    overlay.visibility = View.VISIBLE
                    overlay.alpha = currentSettings.bubbleOpacity
                    renderOverlayContent()
                }
                temporaryHideJob = null
            }
        }
    }

    private fun prepareGalleryScreenshot() {
        val overlay = overlayView ?: return
        if (isAnalyzing || galleryWorkflow.phase == GalleryCaptureWorkflow.Phase.CLEARING) return
        val startedAt = SystemClock.elapsedRealtime()
        val clearedAtMs = System.currentTimeMillis()
        galleryTiming.start(startedAt)
        galleryWorkflow.beginClearing()
        lastResult = null // Settings changes must not recreate the removed marker layer.
        statusMessage = null
        removeMarker()
        overlay.visibility = View.VISIBLE
        overlay.alpha = galleryTiming.alpha(startedAt, currentSettings.bubbleOpacity)
        renderOverlayContent()
        val generation = ++operationGeneration
        analysisJob?.cancel()
        galleryBoundaryTask?.cancel()
        galleryBoundaryTask = serviceScope.async {
            val previousMaxId = LatestGalleryFrameSource(applicationContext).latestImageId()
            GalleryCaptureBoundary(previousMaxId, clearedAtMs)
        }
        // Independent of the recognition job: a click at 0.5s must not cancel the 3s dim timer.
        galleryDimJob?.cancel()
        galleryDimJob = serviceScope.launch {
            delay(galleryTiming.dimDelayMs(SystemClock.elapsedRealtime()))
            if (overlayView === overlay) {
                overlay.alpha = galleryTiming.alpha(SystemClock.elapsedRealtime(), currentSettings.bubbleOpacity)
            }
        }
        analysisJob = serviceScope.launch {
            delay(galleryTiming.readyDelayMs(SystemClock.elapsedRealtime()))
            if (generation == operationGeneration && overlayView === overlay) {
                // Do not wait for MediaStore here. The button becomes interactive at 0.5s;
                // recognition awaits the metadata task if the provider happens to be slow.
                galleryWorkflow.ready()
                renderOverlayContent()
            }
        }
    }

    private fun simulateAnalysis() {
        val overlay = overlayView ?: return
        if (isAnalyzing) return
        val galleryTask = if (currentSettings.imageInputMode == ImageInputMode.LATEST_PHOTO) {
            galleryWorkflow.requireReady()
            checkNotNull(galleryBoundaryTask)
        } else null
        if (currentSettings.hapticsEnabled) {
            overlay.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        isAnalyzing = true
        statusMessage = null
        removeMarker()
        renderOverlayContent()
        // Gallery recognition does not capture this window. Keep it visible and preserve its
        // current dim period, even when recognition starts before the three seconds elapse.
        overlay.visibility = if (galleryTask != null) View.VISIBLE else View.INVISIBLE
        val generation = ++operationGeneration
        analysisJob?.cancel()
        analysisJob = serviceScope.launch {
            try {
                val galleryBoundary = galleryTask?.await()
                val (screenWidth, screenHeight) = screenBounds()
                val result = analysisEngine.analyze(
                    AnalysisRequest(
                        captureDelayMs = currentSettings.captureDelayMs,
                        includeAlternativeCandidates = currentSettings.showTopCandidates,
                        screenWidthPx = screenWidth,
                        screenHeightPx = screenHeight,
                        strategyAlgorithm = currentSettings.strategyAlgorithm,
                        imageInputMode = currentSettings.imageInputMode,
                        galleryCaptureBoundary = galleryBoundary,
                        experienceCalibrationEnabled = currentSettings.experienceCalibrationEnabled,
                        blackBorderDetectionEnabled = currentSettings.blackBorderDetectionEnabled,
                    ),
                )
                if (generation != operationGeneration) return@launch
                lastResult = result
                showBoardOverlay(result)
                if (galleryBoundary != null) galleryWorkflow.reset()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == operationGeneration) {
                    lastResult = null
                    removeMarker()
                    statusMessage = error.message ?: "未知错误"
                }
            } finally {
                if (generation == operationGeneration && overlayView === overlay) {
                    isAnalyzing = false
                    overlay.visibility = View.VISIBLE
                    overlay.alpha = currentSettings.bubbleOpacity
                    renderOverlayContent()
                }
            }
        }
    }

    private fun clearPreviousOverlay(): Boolean {
        if (isAnalyzing) return false
        if (currentSettings.imageInputMode == ImageInputMode.LATEST_PHOTO) {
            prepareGalleryScreenshot()
            return true
        }
        removeMarker()
        lastResult = null
        statusMessage = null
        renderOverlayContent()
        return true
    }

    private fun showBoardOverlay(result: AnalysisResult) {
        removeMarker()
        val (screenWidth, screenHeight) = screenBounds()
        val imageBoard = if (result.source == ResultSource.GALLERY) {
            runCatching { result.geometry.board.mapGalleryToScreen(result.frameWidthPx, result.frameHeightPx, screenWidth, screenHeight) }
                .getOrElse {
                    statusMessage = it.message
                    renderOverlayContent()
                    return
                }
        } else result.geometry.board
        val overlayBoard = imageBoard.toOverlayCoordinates()
        val (usableWidth, usableHeight) = overlayUsableBounds()
        val marker = BoardCoordinateOverlayView(
            context = this,
            board = overlayBoard,
            recommendations = result.recommendations,
            markerColor = currentSettings.markerColor.argb,
            markerStrokeDp = currentSettings.markerStrokeDp,
            showHeaders = currentSettings.showBoardHeaders,
            showKnownObjects = currentSettings.showKnownObjects,
            boardObjects = result.boardObjects,
            boardCells = result.boardCells,
            inventoryAnnotations = if (currentSettings.showInventoryInfo) inventoryOverlayAnnotations(result, overlayBoard) else emptyList(),
            viewportWidthPx = usableWidth,
            viewportHeightPx = usableHeight,
        )
        val params = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(marker, params)
        markerView = marker
    }

    private fun removeMarker() {
        removeViewSafely(markerView)
        markerView = null
    }

    private fun removeViewSafely(view: View?) {
        if (view != null) runCatching { windowManager.removeView(view) }
    }

    private fun overlayDimensions(settings: AppSettings): Pair<Int, Int> {
        val root = overlayView ?: return dp(normalizedBubbleSize(settings.bubbleSizeDp)).let { it to it }
        val (screenWidth, screenHeight) = overlayUsableBounds()
        val button = dp(normalizedBubbleSize(settings.bubbleSizeDp))
        val controlsWidth = if (settings.imageInputMode == ImageInputMode.LATEST_PHOTO) {
            maxOf(button, dp(84f))
        } else button
        val maxWidth = minOf(screenWidth - dp(16f), dp(when (settings.overlayContentMode) {
            OverlayContentMode.BUTTON_ONLY -> 240f
            OverlayContentMode.COMPACT -> 290f
            OverlayContentMode.FULL -> 360f
        })).coerceAtLeast(1)
        val maxHeight = (screenHeight - dp(16f)).coerceAtLeast(1)
        val textWidth = (maxWidth - controlsWidth - dp(10f)).coerceAtLeast(1)
        infoView?.maxWidth = textWidth
        galleryNoticeView?.maxWidth = textWidth
        root.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        return root.measuredWidth.coerceIn(1, maxWidth) to root.measuredHeight.coerceIn(1, maxHeight)
    }

    /** Defer resizing until touch dispatch is finished (same safety rule as drag docking). */
    private fun scheduleContentResize() {
        val root = overlayView ?: return
        if (resizeQueued) return
        resizeQueued = true
        root.post {
            resizeQueued = false
            if (overlayView !== root) return@post
            val params = overlayParams ?: return@post
            val (width, height) = overlayDimensions(currentSettings)
            if (params.width == width && params.height == height) return@post
            params.width = width
            params.height = height
            val placed = calculateDock(params, currentDockSide.takeIf { currentSettings.snapToEdge })
            params.x = placed.x
            params.y = placed.y
            runCatching { windowManager.updateViewLayout(root, params) }
        }
    }

    private fun calculateDock(
        params: WindowManager.LayoutParams,
        preferredSide: DockSide?,
    ): DockPosition {
        val (screenWidth, screenHeight) = overlayUsableBounds()
        return ThreeSideDocking.place(
            x = params.x,
            y = params.y,
            windowWidth = params.width,
            windowHeight = params.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            margin = dp(8f),
            snapEnabled = currentSettings.snapToEdge,
            preferredSide = preferredSide,
        )
    }

    private fun snapAndPersist(params: WindowManager.LayoutParams) {
        if (overlayParams !== params || overlayView == null) return
        val placed = calculateDock(params, preferredSide = null)
        params.x = placed.x
        params.y = placed.y
        currentDockSide = placed.side
        configureOverlayContent()
        overlayView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        serviceScope.launch { repository.saveBubblePosition(placed.x, placed.y) }
    }

    private fun startAsForegroundService() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_grid)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun actionBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xE61B5E55.toInt())
        setStroke(dp(2f), 0xFF9FF4E5.toInt())
    }

    private fun temporaryHideBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8f).toFloat()
        setColor(0xE63B454C.toInt())
        setStroke(dp(1f), 0xFF9FF4E5.toInt())
    }

    private fun closeBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(1f), 0xFFFF6B6B.toInt())
    }

    private fun panelBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18f).toFloat()
        setColor(0xE6262B31.toInt())
        setStroke(dp(1f), 0xAA9FF4E5.toInt())
    }

    private fun initialX(width: Int): Int {
        if (currentSettings.bubbleX >= 0) return currentSettings.bubbleX
        return (screenBounds().first - width - dp(8f)).coerceAtLeast(dp(8f))
    }

    private fun initialY(height: Int): Int {
        if (currentSettings.bubbleY >= 0) return currentSettings.bubbleY
        return (screenBounds().second / 3).coerceAtMost(
            (screenBounds().second - height - dp(8f)).coerceAtLeast(dp(8f)),
        )
    }

    private fun screenBounds(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        @Suppress("DEPRECATION")
        resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /** Overlay origin is already inset; keep badges and adaptive windows outside system bars. */
    private fun overlayUsableBounds(): Pair<Int, Int> {
        val (width, height) = screenBounds()
        if (Build.VERSION.SDK_INT < 30) return width to height
        val insets = windowManager.currentWindowMetrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return (width - insets.left - insets.right).coerceAtLeast(1) to
            (height - insets.top - insets.bottom).coerceAtLeast(1)
    }

    private fun BoardGeometry.toOverlayCoordinates(): BoardGeometry {
        if (Build.VERSION.SDK_INT < 30) return this
        val insets = windowManager.currentWindowMetrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return copy(
            region = region.copy(
                left = region.left - insets.left,
                top = region.top - insets.top,
                right = region.right - insets.left,
                bottom = region.bottom - insets.top,
            ),
            subpixelGrid = subpixelGrid?.copy(
                originX = subpixelGrid.originX - insets.left,
                originY = subpixelGrid.originY - insets.top,
            ),
        )
    }

    // Standalone Service window uses platform styling, with all colours/backgrounds set explicitly.
    @SuppressLint("AppCompatCustomView")
    private class AccessibleActionView(context: Context) : TextView(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    companion object {
        private const val CHANNEL_ID = "overlay_assistant"
        private const val NOTIFICATION_ID = 2102
        private const val EXTRA_REQUEST_ID = "assistant_request_id"
        private const val CLOSE_BUTTON_TEXT_SIZE_SP = 15f

        fun start(context: Context, requestId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java).putExtra(EXTRA_REQUEST_ID, requestId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
