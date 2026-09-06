package com.bagridmaster.app.capture

import com.bagridmaster.app.vision.RgbaFrame
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureFrameBrokerTest {
    private val frame = RgbaFrame(1, 1, 4, byteArrayOf(0, 0, 0, -1), 123L)

    @Test fun deliversOnlyToTheRequestThatStartedTheCopy() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { CaptureFrameBroker.capture() }
        val stale = CaptureFrameBroker.currentTicket()!!
        first.cancelAndJoin()
        val second = async(start = CoroutineStart.UNDISPATCHED) { CaptureFrameBroker.capture() }
        val fresh = CaptureFrameBroker.currentTicket()!!
        assertFalse(CaptureFrameBroker.offer(stale, frame))
        assertFalse(CaptureFrameBroker.fail(stale, "stale failure"))
        assertTrue(second.isActive)
        assertTrue(CaptureFrameBroker.offer(fresh, frame))
        assertSame(frame, second.await())
        assertFalse(CaptureFrameBroker.wantsFrame)
    }

    @Test fun timeoutIsAnActionableFailureNotCoroutineCancellationAndKeepsRejectedFrame() = runBlocking {
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { CaptureFrameBroker.capture(30) }.exceptionOrNull()
        }
        CaptureFrameBroker.note(CaptureFrameBroker.currentTicket()!!, "异常帧重新同步中", frame)
        val error = result.await()
        assertTrue(error is CaptureFrameException)
        assertFalse(error is CancellationException)
        assertTrue(error!!.message!!.contains("异常帧重新同步中"))
        assertSame(frame, (error as CaptureFrameException).rejectedFrame)
        assertFalse(CaptureFrameBroker.wantsFrame)
    }

    @Test fun stoppingSessionWakesPendingRecognition() = runBlocking {
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { CaptureFrameBroker.capture() }.exceptionOrNull()
        }
        CaptureFrameBroker.fail("屏幕捕获已停止")
        assertEquals("屏幕捕获已停止", result.await()!!.message)
        assertFalse(CaptureFrameBroker.wantsFrame)
    }

    @Test fun externalCancellationStillPropagatesAndCleansPendingRequest() = runBlocking {
        val result = async(start = CoroutineStart.UNDISPATCHED) { CaptureFrameBroker.capture() }
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertNull(CaptureFrameBroker.currentTicket())
    }

    @Test fun concurrentRecognitionIsRejectedWithoutOverwritingFirstRequest() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { CaptureFrameBroker.capture() }
        val ticket = CaptureFrameBroker.currentTicket()!!
        val error = runCatching { CaptureFrameBroker.capture() }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("已有截图请求"))
        assertSame(ticket, CaptureFrameBroker.currentTicket())
        assertTrue(CaptureFrameBroker.offer(ticket, frame))
        assertSame(frame, first.await())
    }
}
