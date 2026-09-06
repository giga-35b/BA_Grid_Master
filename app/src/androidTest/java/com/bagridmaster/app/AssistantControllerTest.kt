package com.bagridmaster.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.bagridmaster.app.assistant.AssistantController
import com.bagridmaster.app.assistant.AssistantPhase
import com.bagridmaster.app.capture.CaptureRuntimeState
import com.bagridmaster.app.capture.CaptureSessionService
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.overlay.OverlayService
import org.junit.Assert.*
import org.junit.Test

/** Service calls are recorded, never forwarded: these tests do not start real overlays/capture. */
class AssistantControllerTest {
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<String>()
        val stops = mutableListOf<String>()
        var failStart = false
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName? {
            if (failStart) throw SecurityException("test: start denied")
            val component = checkNotNull(service.component)
            starts += component.className
            return component
        }
        override fun stopService(name: Intent): Boolean {
            stops += checkNotNull(name.component).className
            return true
        }
    }

    private fun scenario(test: (RecordingContext) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = RecordingContext(instrumentation.targetContext)
            AssistantController.stop(context)
            context.stops.clear()
            try { test(context) }
            finally {
                AssistantController.stop(context)
                CaptureRuntimeState.setActive(false)
            }
        }
    }

    @Test fun deniedConsentNeverStartsEitherService() = scenario { context ->
        val id = AssistantController.begin(context, ImageInputMode.SCREEN_CAPTURE)
        assertTrue(context.starts.isEmpty())
        AssistantController.consentResult(context, id, Activity.RESULT_CANCELED, null)
        assertTrue(context.starts.isEmpty())
        assertEquals(AssistantPhase.STOPPED, AssistantController.status.value.phase)
        assertTrue(AssistantController.status.value.message!!.startsWith("获取屏幕授权失败"))
    }

    @Test fun successfulCaptureStartsOverlayOnlyAfterReadyAndRevocationStopsBoth() = scenario { context ->
        val id = AssistantController.begin(context, ImageInputMode.SCREEN_CAPTURE)
        AssistantController.consentResult(context, id, Activity.RESULT_OK, Intent())
        assertEquals(listOf(CaptureSessionService::class.java.name), context.starts)
        assertFalse(AssistantController.acceptsOverlay(id))
        CaptureRuntimeState.setActive(true)
        AssistantController.captureReady(context, id)
        assertEquals(listOf(CaptureSessionService::class.java.name, OverlayService::class.java.name), context.starts)
        assertTrue(AssistantController.acceptsOverlay(id))
        AssistantController.overlayReady(id)
        AssistantController.captureStopped(context, id)
        assertEquals(AssistantPhase.STOPPED, AssistantController.status.value.phase)
        assertTrue(context.stops.containsAll(listOf(CaptureSessionService::class.java.name, OverlayService::class.java.name)))
    }

    @Test fun foregroundCaptureFailureKeepsOverlayOff() = scenario { context ->
        context.failStart = true
        val id = AssistantController.begin(context, ImageInputMode.SCREEN_CAPTURE)
        AssistantController.consentResult(context, id, Activity.RESULT_OK, Intent())
        assertTrue(context.starts.isEmpty())
        assertEquals(AssistantPhase.STOPPED, AssistantController.status.value.phase)
        assertTrue(AssistantController.status.value.message!!.startsWith("获取屏幕授权失败"))
    }

    @Test fun galleryNeverStartsCaptureAndIgnoresOldCaptureCallback() = scenario { context ->
        val oldId = AssistantController.begin(context, ImageInputMode.SCREEN_CAPTURE)
        AssistantController.stop(context)
        val id = AssistantController.begin(context, ImageInputMode.LATEST_PHOTO)
        assertEquals(listOf(OverlayService::class.java.name), context.starts)
        AssistantController.consentResult(context, oldId, Activity.RESULT_OK, Intent())
        AssistantController.captureStopped(context, oldId)
        assertEquals(AssistantPhase.OVERLAY, AssistantController.status.value.phase)
        assertEquals(1, context.starts.size)
        assertTrue(AssistantController.acceptsOverlay(id))
    }
}
