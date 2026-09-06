package com.bagridmaster.app.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.bagridmaster.app.capture.CaptureRuntimeState
import com.bagridmaster.app.capture.CaptureSessionService
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.overlay.OverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner of both services, independent of the configuration Activity's lifecycle. */
object AssistantController {
    private val workflow = AssistantWorkflow()
    private val mutableStatus = MutableStateFlow(workflow.status)
    val status = mutableStatus.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())

    fun begin(context: Context, mode: ImageInputMode): Long {
        if (workflow.status.phase != AssistantPhase.STOPPED) return workflow.status.requestId
        val id = workflow.begin(mode)
        publish()
        if (mode == ImageInputMode.LATEST_PHOTO) startOverlay(context, id)
        return id
    }

    fun consentResult(context: Context, id: Long, resultCode: Int, data: Intent?) {
        if (!workflow.advance(id, AssistantPhase.CONSENT, AssistantPhase.CAPTURE)) return
        publish()
        if (resultCode != Activity.RESULT_OK || data == null) {
            fail(context, id, "获取屏幕授权失败：授权被取消或拒绝")
            return
        }
        runCatching { CaptureSessionService.start(context, resultCode, data, id) }
            .onFailure { fail(context, id, "获取屏幕授权失败：无法启动屏幕捕获服务") }
        watchStartup(context, id, AssistantPhase.CAPTURE, "获取屏幕授权失败：屏幕捕获启动超时")
    }

    fun acceptsCapture(id: Long): Boolean = workflow.isCurrent(id) &&
        workflow.status.phase == AssistantPhase.CAPTURE && workflow.status.mode == ImageInputMode.SCREEN_CAPTURE

    fun captureReady(context: Context, id: Long) {
        if (workflow.advance(id, AssistantPhase.CAPTURE, AssistantPhase.OVERLAY)) {
            publish()
            startOverlay(context, id)
        }
    }

    fun acceptsOverlay(id: Long): Boolean = workflow.isCurrent(id) &&
        workflow.status.phase == AssistantPhase.OVERLAY &&
        (workflow.status.mode == ImageInputMode.LATEST_PHOTO || CaptureRuntimeState.isActive.value)

    fun overlayReady(id: Long) {
        if (workflow.advance(id, AssistantPhase.OVERLAY, AssistantPhase.RUNNING)) publish()
    }

    fun captureStopped(context: Context, id: Long) {
        if (!workflow.isCurrent(id) || workflow.status.mode != ImageInputMode.SCREEN_CAPTURE) return
        val message = if (workflow.status.starting) "获取屏幕授权失败：捕获会话未能建立"
            else "屏幕捕获已结束，悬浮助手已停用"
        fail(context, id, message)
    }

    fun overlayStopped(context: Context, id: Long) {
        if (workflow.isCurrent(id)) stop(context)
    }

    fun fail(context: Context, id: Long, message: String) {
        if (!workflow.isCurrent(id)) return
        stop(context, message)
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }

    fun stop(context: Context, message: String? = null) {
        // Invalidate before stopping services: their callbacks must not recursively stop a new session.
        workflow.stop(message)
        publish()
        OverlayService.stop(context)
        CaptureSessionService.stop(context)
    }

    private fun startOverlay(context: Context, id: Long) {
        runCatching { OverlayService.start(context, id) }
            .onFailure { fail(context, id, "悬浮助手启动失败，请检查悬浮窗权限") }
        watchStartup(context, id, AssistantPhase.OVERLAY, "悬浮助手启动失败：启动超时")
    }

    private fun watchStartup(context: Context, id: Long, phase: AssistantPhase, message: String) {
        val appContext = context.applicationContext
        handler.postDelayed({
            if (workflow.isCurrent(id) && workflow.status.phase == phase) fail(appContext, id, message)
        }, 10_000L)
    }

    private fun publish() { mutableStatus.value = workflow.status }
}
