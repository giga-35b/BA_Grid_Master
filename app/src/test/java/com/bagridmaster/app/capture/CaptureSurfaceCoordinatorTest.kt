package com.bagridmaster.app.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureSurfaceCoordinatorTest {
    private val portrait = CaptureSize(1080, 2400, 440)
    private val landscape = CaptureSize(2400, 1080, 440)

    @Test fun startupCreatesOneDisplayAndDuplicateSizeDoesNotResetWarmup() {
        val h = Harness()
        assertTrue(h.coordinator.resize(portrait))
        h.scheduler.advance(0)
        val token = h.backend.token!!
        assertEquals(listOf("reader:1080x2400", "create:1080x2400"), h.backend.events)
        assertFalse(h.coordinator.canRead(token))
        assertFalse(h.coordinator.resize(portrait))
        h.scheduler.advance(120)
        assertTrue(h.coordinator.canRead(token))
        assertEquals(1, h.backend.drains)
        assertEquals(1, h.starts)
    }

    @Test fun rotationDetachesBeforeResizeAndAttachesAfterSeparateSettlingPhase() {
        val h = Harness().started(portrait)
        val old = h.backend.token!!
        h.backend.events.clear()
        h.coordinator.resize(landscape)
        assertFalse(h.coordinator.deliverIfCurrent(old) { fail("Old frame escaped") })
        h.scheduler.advance(0)
        assertEquals(listOf("detach"), h.backend.events)
        h.scheduler.advance(119)
        assertEquals(listOf("detach"), h.backend.events)
        h.scheduler.advance(1)
        assertEquals(listOf("detach", "resize:2400x1080", "reader:2400x1080"), h.backend.events)
        h.scheduler.advance(79)
        assertFalse(h.backend.attached)
        h.scheduler.advance(1)
        assertEquals("attach:2400x1080", h.backend.events.last())
        val fresh = h.backend.token!!
        assertFalse(h.coordinator.canRead(fresh))
        h.scheduler.advance(120)
        assertTrue(h.coordinator.canRead(fresh))
        assertEquals(1, h.backend.creates)
    }

    @Test fun quickRotationsSupersedeQueuedWorkIncludingAnAlreadyPreparedReader() {
        val h = Harness().started(portrait)
        h.coordinator.resize(landscape)
        h.scheduler.advance(120) // New landscape reader exists but has not been attached.
        val obsolete = h.backend.token!!
        h.coordinator.resize(portrait)
        h.scheduler.advance(80)
        assertFalse(h.coordinator.isCurrent(obsolete))
        assertFalse(h.backend.attached)
        h.scheduler.advance(240)
        assertEquals(portrait, h.backend.token!!.size)
        assertTrue(h.backend.attached)
        assertEquals(1, h.backend.creates)
        assertFalse(h.backend.events.contains("attach:2400x1080"))
    }

    @Test fun stopDuringResizeCannotReattachOrDeliverEvenBeforeReleaseRuns() {
        val h = Harness().started(portrait)
        h.coordinator.resize(landscape)
        h.scheduler.advance(120)
        val token = h.backend.token!!
        h.coordinator.close()
        assertFalse(h.coordinator.deliverIfCurrent(token) { fail() })
        assertFalse(h.coordinator.resize(portrait))
        h.coordinator.close()
        h.scheduler.advance(1000)
        assertEquals(1, h.backend.releases)
        assertEquals("release", h.backend.events.last())
    }

    @Test fun closeBeforeFirstTaskNeverCreatesADisplay() {
        val h = Harness()
        h.coordinator.resize(portrait)
        h.coordinator.close()
        h.scheduler.advance(1000)
        assertEquals(0, h.backend.creates)
        assertEquals(0, h.starts)
        assertEquals(1, h.backend.releases)
    }

    @Test fun recoveryIsBoundedAndNeverReusesConsentToCreateAnotherDisplay() {
        val h = Harness().started(landscape)
        val original = h.backend.token!!
        assertTrue(h.coordinator.recover(original))
        assertFalse(h.coordinator.recover(original))
        h.scheduler.advance(320)
        val recovered = h.backend.token!!
        assertEquals(1, recovered.recoveries)
        assertTrue(h.coordinator.canRead(recovered))
        assertFalse(h.coordinator.recover(recovered))
        assertFalse(h.coordinator.resize(landscape)) // Duplicate callback must not reset retry budget.
        assertEquals(1, h.backend.creates)
        h.coordinator.resize(portrait)
        h.scheduler.advance(320)
        assertEquals(0, h.backend.token!!.recoveries)
    }

    @Test fun resizeFailureReleasesAndReportsInsteadOfContinuingWithOldSurface() {
        val h = Harness().started(portrait)
        h.backend.failResize = true
        h.coordinator.resize(landscape)
        h.scheduler.advance(1000)
        assertEquals(1, h.failures.size)
        assertEquals("resize failed", h.failures.single().message)
        assertEquals(1, h.backend.releases)
        assertFalse(h.backend.attached)
        assertFalse(h.coordinator.resize(portrait))
    }

    @Test fun latestSizeWinsBeforeInitialDisplayCreation() {
        val h = Harness()
        h.coordinator.resize(portrait)
        h.coordinator.resize(landscape)
        h.scheduler.advance(120)
        assertEquals(landscape, h.backend.token!!.size)
        assertEquals(1, h.backend.creates)
        assertEquals(1, h.starts)
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val backend = FakeBackend()
        var starts = 0
        val failures = mutableListOf<Throwable>()
        val coordinator = CaptureSurfaceCoordinator(scheduler, backend, { starts++ }, { failures += it })
        fun started(size: CaptureSize) = apply { coordinator.resize(size); scheduler.advance(120) }
    }

    private class FakeScheduler : CaptureScheduler {
        override var nowMs = 0L
        private data class Job(val at: Long, val order: Int, val action: () -> Unit)
        private var sequence = 0
        private val jobs = mutableListOf<Job>()
        override fun post(delayMs: Long, action: () -> Unit) { jobs += Job(nowMs + delayMs, sequence++, action) }
        fun advance(ms: Long) {
            val until = nowMs + ms
            while (true) {
                val next = jobs.filter { it.at <= until }.minWithOrNull(compareBy<Job> { it.at }.thenBy { it.order }) ?: break
                jobs.remove(next)
                nowMs = next.at
                next.action()
            }
            nowMs = until
        }
    }

    private class FakeBackend : CaptureSurfaceBackend {
        val events = mutableListOf<String>()
        var token: CaptureSurfaceToken? = null
        var attached = false
        var creates = 0
        var drains = 0
        var releases = 0
        var failResize = false
        private fun CaptureSize.label() = "${width}x$height"
        override fun prepareReader(token: CaptureSurfaceToken) { this.token = token; events += "reader:${token.size.label()}" }
        override fun createDisplay(size: CaptureSize) { creates++; attached = true; events += "create:${size.label()}" }
        override fun detach() { attached = false; events += "detach" }
        override fun resize(size: CaptureSize) {
            check(!attached) { "Must not resize against old Surface" }
            if (failResize) error("resize failed")
            events += "resize:${size.label()}"
        }
        override fun attach() { attached = true; events += "attach:${token!!.size.label()}" }
        override fun drain() { drains++ }
        override fun release() { releases++; attached = false; events += "release" }
    }
}
