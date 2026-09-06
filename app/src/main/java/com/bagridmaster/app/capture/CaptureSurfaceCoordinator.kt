package com.bagridmaster.app.capture

internal data class CaptureSize(val width: Int, val height: Int, val densityDpi: Int) {
    init { require(width > 0 && height > 0 && densityDpi > 0) }
}

internal data class CaptureSurfaceToken(val generation: Long, val size: CaptureSize, val recoveries: Int = 0)

/** Resource operations are serialized by the scheduler; invalidation is immediate on the caller. */
internal interface CaptureScheduler {
    val nowMs: Long
    fun post(delayMs: Long = 0, action: () -> Unit)
}

internal interface CaptureSurfaceBackend {
    fun prepareReader(token: CaptureSurfaceToken)
    fun createDisplay(size: CaptureSize)
    fun detach()
    fun resize(size: CaptureSize)
    fun attach()
    fun drain()
    fun release()
}

/**
 * A single VirtualDisplay per consent token. Resizing while its old Surface is still attached can
 * leave a vendor ContentRecorder using the old mirror scale/offset. Keep it detached across a
 * compositor traversal, resize, then attach a correctly sized Surface in a separate phase.
 * The delays are a settling allowance, not a readiness guarantee: frames are also inspected.
 */
internal class CaptureSurfaceCoordinator(
    private val scheduler: CaptureScheduler,
    private val backend: CaptureSurfaceBackend,
    private val onStarted: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var current: CaptureSurfaceToken? = null
    private var closed = false
    private var nextGeneration = 0L
    // Only the capture scheduler accesses these fields.
    private var displayCreated = false
    private var readyAtMs = Long.MAX_VALUE

    fun resize(size: CaptureSize): Boolean {
        val token = synchronized(lock) {
            if (closed || current?.size == size) return false
            CaptureSurfaceToken(++nextGeneration, size).also { current = it }
        }
        schedule(token) { replaceSurface(token) }
        return true
    }

    /** At most one rebuild for a bad frame at a given size, never another createVirtualDisplay. */
    fun recover(token: CaptureSurfaceToken): Boolean {
        val retry = synchronized(lock) {
            if (closed || current != token || token.recoveries >= 1) return false
            token.copy(generation = ++nextGeneration, recoveries = token.recoveries + 1)
                .also { current = it }
        }
        schedule(retry) { replaceSurface(retry) }
        return true
    }

    fun isCurrent(token: CaptureSurfaceToken): Boolean = synchronized(lock) { !closed && current == token }

    // Called on the capture scheduler, like all reader callbacks.
    fun canRead(token: CaptureSurfaceToken): Boolean = isCurrent(token) && scheduler.nowMs >= readyAtMs

    /** Do not publish a copied old frame if a rotation/stop invalidated it while copying. */
    fun deliverIfCurrent(token: CaptureSurfaceToken, action: () -> Unit): Boolean = synchronized(lock) {
        if (closed || current != token) false else { action(); true }
    }

    fun close() { closeIfCurrent() }

    private fun closeIfCurrent(expected: CaptureSurfaceToken? = null): Boolean {
        synchronized(lock) {
            if (closed || (expected != null && current != expected)) return false
            closed = true
            current = null
        }
        scheduler.post { backend.release() }
        return true
    }

    private fun replaceSurface(token: CaptureSurfaceToken) {
        readyAtMs = Long.MAX_VALUE
        if (!displayCreated) {
            backend.prepareReader(token)
            backend.createDisplay(token.size)
            displayCreated = true
            warmUp(token)
            onStarted()
            return
        }
        backend.detach()
        schedule(token, DETACH_SETTLE_MS) {
            backend.resize(token.size)
            backend.prepareReader(token)
            schedule(token, RESIZE_SETTLE_MS) {
                backend.attach()
                warmUp(token)
            }
        }
    }

    private fun warmUp(token: CaptureSurfaceToken) {
        readyAtMs = scheduler.nowMs + WARM_UP_MS
        // Leave frames queued during warm-up. Even a static screen producing only one buffer
        // must be drained when settling finishes; don't require N more frame callbacks.
        schedule(token, WARM_UP_MS) { backend.drain() }
    }

    private fun schedule(token: CaptureSurfaceToken, delayMs: Long = 0, action: () -> Unit) {
        scheduler.post(delayMs) {
            if (!isCurrent(token)) return@post
            runCatching(action).onFailure { error ->
                if (closeIfCurrent(token)) onFailure(error)
            }
        }
    }

    companion object {
        internal const val DETACH_SETTLE_MS = 120L
        internal const val RESIZE_SETTLE_MS = 80L
        internal const val WARM_UP_MS = 120L
    }
}
