package com.bagridmaster.app.assistant

import com.bagridmaster.app.model.ImageInputMode
import org.junit.Assert.*
import org.junit.Test

class AssistantWorkflowTest {
    @Test fun screenModeCannotShowOverlayBeforeConsentAndCaptureReady() {
        val flow = AssistantWorkflow()
        val id = flow.begin(ImageInputMode.SCREEN_CAPTURE)
        assertEquals(AssistantPhase.CONSENT, flow.status.phase)
        assertFalse(flow.advance(id, AssistantPhase.OVERLAY, AssistantPhase.RUNNING))
        assertTrue(flow.advance(id, AssistantPhase.CONSENT, AssistantPhase.CAPTURE))
        assertFalse(flow.advance(id, AssistantPhase.OVERLAY, AssistantPhase.RUNNING))
        assertTrue(flow.advance(id, AssistantPhase.CAPTURE, AssistantPhase.OVERLAY))
        assertTrue(flow.advance(id, AssistantPhase.OVERLAY, AssistantPhase.RUNNING))
        assertFalse(flow.status.starting)
    }

    @Test fun galleryModeSkipsCaptureEntirely() {
        val flow = AssistantWorkflow()
        val id = flow.begin(ImageInputMode.LATEST_PHOTO)
        assertEquals(AssistantPhase.OVERLAY, flow.status.phase)
        assertFalse(flow.advance(id, AssistantPhase.CONSENT, AssistantPhase.CAPTURE))
        assertFalse(flow.advance(id, AssistantPhase.CAPTURE, AssistantPhase.OVERLAY))
        assertTrue(flow.advance(id, AssistantPhase.OVERLAY, AssistantPhase.RUNNING))
    }

    @Test fun cancelOrCaptureFailureKeepsOverlayStopped() {
        for (captureStarted in listOf(false, true)) {
            val flow = AssistantWorkflow()
            val id = flow.begin(ImageInputMode.SCREEN_CAPTURE)
            if (captureStarted) flow.advance(id, AssistantPhase.CONSENT, AssistantPhase.CAPTURE)
            flow.stop("获取屏幕授权失败")
            assertFalse(flow.isCurrent(id))
            assertFalse(flow.status.starting)
            assertEquals("获取屏幕授权失败", flow.status.message)
            assertFalse(flow.advance(id, AssistantPhase.CAPTURE, AssistantPhase.OVERLAY))
        }
    }

    @Test fun oldConsentOrServiceCallbackCannotAdvanceNewSession() {
        val flow = AssistantWorkflow()
        val old = flow.begin(ImageInputMode.SCREEN_CAPTURE)
        flow.stop()
        val current = flow.begin(ImageInputMode.LATEST_PHOTO)
        assertNotEquals(old, current)
        assertFalse(flow.isCurrent(old))
        assertFalse(flow.advance(old, AssistantPhase.CONSENT, AssistantPhase.CAPTURE))
        assertFalse(flow.advance(old, AssistantPhase.OVERLAY, AssistantPhase.RUNNING))
        assertEquals(AssistantPhase.OVERLAY, flow.status.phase)
    }

    @Test fun revocationInvalidatesRunningSessionAndRetryClearsMessage() {
        val flow = AssistantWorkflow()
        val id = flow.begin(ImageInputMode.SCREEN_CAPTURE)
        flow.advance(id, AssistantPhase.CONSENT, AssistantPhase.CAPTURE)
        flow.advance(id, AssistantPhase.CAPTURE, AssistantPhase.OVERLAY)
        flow.advance(id, AssistantPhase.OVERLAY, AssistantPhase.RUNNING)
        flow.stop("屏幕捕获已结束，悬浮助手已停用")
        assertEquals(AssistantPhase.STOPPED, flow.status.phase)
        assertFalse(flow.isCurrent(id))
        flow.begin(ImageInputMode.SCREEN_CAPTURE)
        assertNull(flow.status.message)
    }
}
