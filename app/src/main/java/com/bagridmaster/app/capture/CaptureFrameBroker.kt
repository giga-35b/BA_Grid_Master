package com.bagridmaster.app.capture

import com.bagridmaster.app.vision.RgbaFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class CaptureFrameException(message: String, val rejectedFrame: RgbaFrame? = null) : IllegalStateException(message)

/** Hands exactly one fresh MediaProjection frame to the analysis engine on demand. */
object CaptureFrameBroker {
    private val lock = Any()
    internal class Ticket {
        val result = CompletableDeferred<RgbaFrame>()
        var note = "未收到就绪的截图帧，请等待画面稳定后重试"
        var rejectedFrame: RgbaFrame? = null
    }
    private var pending: Ticket? = null

    val wantsFrame: Boolean
        get() = currentTicket() != null

    internal fun currentTicket(): Ticket? = synchronized(lock) { pending?.takeIf { it.result.isActive } }

    suspend fun capture(timeoutMs: Long = 3_000L): RgbaFrame {
        val request = synchronized(lock) {
            check(pending?.result?.isActive != true) { "已有截图请求正在进行" }
            Ticket().also { pending = it }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { request.result.await() }
                ?: throw synchronized(lock) { CaptureFrameException("屏幕捕获超时：${request.note}", request.rejectedFrame) }
        } finally {
            synchronized(lock) {
                if (pending === request) pending = null
                request.result.cancel()
            }
        }
    }

    internal fun offer(ticket: Ticket, frame: RgbaFrame) = synchronized(lock) {
        if (pending === ticket) ticket.result.complete(frame) else false
    }

    internal fun note(ticket: Ticket, message: String, frame: RgbaFrame? = null) = synchronized(lock) {
        if (pending === ticket) {
            ticket.note = message
            if (frame != null) ticket.rejectedFrame = frame
        }
    }

    internal fun fail(ticket: Ticket, message: String, frame: RgbaFrame? = null) = synchronized(lock) {
        if (pending === ticket) ticket.result.completeExceptionally(CaptureFrameException(message, frame ?: ticket.rejectedFrame)) else false
    }

    internal fun fail(message: String) {
        synchronized(lock) { pending?.result?.completeExceptionally(IllegalStateException(message)) }
    }
}
