package net.packrig.audio

import net.packrig.core.AppInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * CaptureLifecycle serializes AudioEngine start/stop onto a dedicated thread so
 * AudioRecord framework calls that can wedge on a dying USB device never run on
 * the caller's (main) thread — the 2026-07-03 field-ANR vector.
 */
class CaptureLifecycleTest {

    /** Records which thread ran each op and can block inside start/stop on demand. */
    private class RecordingEngine : AudioEngine {
        override val outputSampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ

        val ops: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val startThread = AtomicReference<Thread>()
        val stopThread = AtomicReference<Thread>()

        /** When set, start() awaits this latch before returning/throwing. */
        var startGate: CountDownLatch? = null

        /** When set, stop() awaits this latch before returning. */
        var stopGate: CountDownLatch? = null

        /** When set, start() throws after passing the gate. */
        var startFailure: Throwable? = null

        override fun start(preferredDeviceId: Int?, onFrames: (ShortArray) -> Unit) {
            startThread.set(Thread.currentThread())
            startGate?.await(5, TimeUnit.SECONDS)
            startFailure?.let { throw it }
            ops.add("start")
        }

        override fun stop() {
            stopThread.set(Thread.currentThread())
            stopGate?.await(5, TimeUnit.SECONDS)
            ops.add("stop")
        }
    }

    private val engine = RecordingEngine()
    private val lifecycle = CaptureLifecycle(engine)

    @After
    fun tearDown() {
        engine.startGate?.countDown()
        engine.stopGate?.countDown()
        lifecycle.close()
    }

    private fun awaitIdle() {
        val done = CountDownLatch(1)
        lifecycle.stop { done.countDown() }
        assertTrue("lifecycle thread did not drain", done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `start runs the engine off the calling thread`() {
        lifecycle.start(preferredDeviceId = 7, onFrames = {}, onStartFailed = {})
        awaitIdle()
        assertNotEquals(Thread.currentThread(), engine.startThread.get())
        assertEquals(listOf("start", "stop"), engine.ops)
    }

    @Test
    fun `stop runs the engine off the calling thread`() {
        lifecycle.stop()
        awaitIdle()
        assertNotEquals(Thread.currentThread(), engine.stopThread.get())
    }

    @Test
    fun `stop then start execute in order on the same thread`() {
        // selectDevice()/restartCapture() shape: stop the old session, start the new.
        lifecycle.start(preferredDeviceId = 1, onFrames = {}, onStartFailed = {})
        lifecycle.stop()
        lifecycle.start(preferredDeviceId = 2, onFrames = {}, onStartFailed = {})
        awaitIdle()
        assertEquals(listOf("start", "stop", "start", "stop"), engine.ops)
        assertEquals(engine.startThread.get(), engine.stopThread.get())
    }

    @Test
    fun `queued start waits for a slow stop to finish`() {
        val slowStop = CountDownLatch(1)
        engine.stopGate = slowStop
        lifecycle.stop()
        lifecycle.start(preferredDeviceId = null, onFrames = {}, onStartFailed = {})

        // The wedged stop() must not let start() jump the queue.
        Thread.sleep(100)
        assertEquals(emptyList<String>(), engine.ops)

        slowStop.countDown()
        engine.stopGate = null
        awaitIdle()
        assertEquals(listOf("stop", "start", "stop"), engine.ops)
    }

    @Test
    fun `onStopped runs after the engine actually stopped`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        lifecycle.stop {
            order.add("onStopped(engine.ops=${engine.ops.toList()})")
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("onStopped(engine.ops=[stop])"), order)
    }

    @Test
    fun `start failure reports to onStartFailed`() {
        engine.startFailure = IllegalStateException("Unable to open any AudioRecord configuration")
        val failure = AtomicReference<Throwable>()
        val failed = CountDownLatch(1)
        lifecycle.start(preferredDeviceId = null, onFrames = {}) { t ->
            failure.set(t)
            failed.countDown()
        }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals("Unable to open any AudioRecord configuration", failure.get()?.message)
    }

    @Test
    fun `start failure is swallowed when a newer op was already issued`() {
        // A failing start whose correction would land after a subsequent
        // stop/start pair must not stomp the newer session's state.
        val gate = CountDownLatch(1)
        engine.startGate = gate
        engine.startFailure = IllegalStateException("boom")
        val failed = CountDownLatch(1)
        lifecycle.start(preferredDeviceId = 1, onFrames = {}) { failed.countDown() }

        // Newer ops issued while the first start is still wedged in the framework.
        lifecycle.stop()
        engine.startGate = null
        engine.startFailure = null
        lifecycle.start(preferredDeviceId = 2, onFrames = {}, onStartFailed = {})

        gate.countDown()
        awaitIdle()
        assertFalse("stale failure must be suppressed", failed.await(150, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `close stops the engine and terminates the lifecycle thread`() {
        lifecycle.start(preferredDeviceId = null, onFrames = {}, onStartFailed = {})
        lifecycle.close()
        assertTrue("executor did not terminate", lifecycle.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf("start", "stop"), engine.ops)
    }

    @Test
    fun `ops after close are ignored instead of crashing`() {
        lifecycle.close()
        assertTrue(lifecycle.awaitTermination(5, TimeUnit.SECONDS))
        lifecycle.start(preferredDeviceId = null, onFrames = {}, onStartFailed = {})
        lifecycle.stop()
        // Only the close-time stop ran; post-close ops were dropped.
        assertEquals(listOf("stop"), engine.ops)
    }
}
