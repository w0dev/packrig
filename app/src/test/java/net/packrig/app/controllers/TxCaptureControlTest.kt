package net.packrig.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * TX must not key PTT while the capture engine still holds the USB codec:
 * [TxCaptureControl.pauseForTx] has to suspend until the queued capture stop
 * actually completes (2026-07-03 field report — intermittent zero RF power
 * because TX audio raced capture teardown after the stop went async).
 */
class TxCaptureControlTest {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @After fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `pauseForTx does not return until the capture stop completes`() {
        val stopCallback = AtomicReference<(() -> Unit)?>(null)
        val control = TxCaptureControl(
            isCapturing = { true },
            isOperating = { true },
            stopCapture = { onStopped -> stopCallback.set(onStopped) },
            beginCapture = {},
        )

        val paused = CountDownLatch(1)
        scope.launch {
            control.pauseForTx()
            paused.countDown()
        }

        // Stop has been requested but not completed — pauseForTx must still be suspended.
        waitUntil { stopCallback.get() != null }
        assertFalse(
            "pauseForTx returned before the capture engine stopped",
            paused.await(150, TimeUnit.MILLISECONDS),
        )

        stopCallback.get()!!.invoke()
        assertTrue(
            "pauseForTx did not resume after the capture stop completed",
            paused.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `pauseForTx gives up after the bounded timeout when the stop wedges`() = runBlocking {
        val control = TxCaptureControl(
            isCapturing = { true },
            isOperating = { true },
            stopCapture = { _ -> /* engine wedged: completion never arrives */ },
            beginCapture = {},
            stopAwaitTimeoutMs = 100L,
        )
        val startedAt = System.currentTimeMillis()
        control.pauseForTx()
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("expected a bounded wait, took ${elapsed}ms", elapsed in 100..1_999)
    }

    @Test
    fun `pauseForTx is a no-op when capture is not running`() = runBlocking {
        val stops = AtomicInteger(0)
        val control = TxCaptureControl(
            isCapturing = { false },
            isOperating = { true },
            stopCapture = { _ -> stops.incrementAndGet() },
            beginCapture = {},
        )
        control.pauseForTx()
        assertEquals(0, stops.get())
    }

    @Test
    fun `resumeAfterTx restarts capture only while operating and not capturing`() {
        val begins = AtomicInteger(0)
        var operating = true
        var capturing = false
        val control = TxCaptureControl(
            isCapturing = { capturing },
            isOperating = { operating },
            stopCapture = { _ -> },
            beginCapture = { begins.incrementAndGet() },
        )

        control.resumeAfterTx()
        assertEquals(1, begins.get())

        capturing = true
        control.resumeAfterTx()
        assertEquals(1, begins.get())

        capturing = false
        operating = false
        control.resumeAfterTx()
        assertEquals(1, begins.get())
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
    }
}
