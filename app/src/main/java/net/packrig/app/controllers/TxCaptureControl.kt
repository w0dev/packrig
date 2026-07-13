package net.packrig.app.controllers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges [TxOrchestrator.CaptureControl] onto the ViewModel's capture
 * start/stop, which run asynchronously on the CaptureLifecycle thread.
 *
 * [pauseForTx] suspends until the queued capture stop actually completes so
 * TX never opens the USB playback path while the capture engine still holds
 * the codec (2026-07-03 field report: intermittent zero RF power — the rig
 * keyed but TX audio lost the race against capture teardown). The wait is
 * bounded by [stopAwaitTimeoutMs]: a wedged engine must not hold TX hostage —
 * at that point capture is torn down enough that playback can proceed, and
 * the RELY-02/03 watchdogs recover the RX path afterwards.
 */
class TxCaptureControl(
    private val isCapturing: () -> Boolean,
    private val isOperating: () -> Boolean,
    private val stopCapture: (onStopped: () -> Unit) -> Unit,
    private val beginCapture: () -> Unit,
    private val stopAwaitTimeoutMs: Long = STOP_AWAIT_TIMEOUT_MS_DEFAULT,
) : TxOrchestrator.CaptureControl {

    override suspend fun pauseForTx() {
        if (!isCapturing()) return
        val stopped = CompletableDeferred<Unit>()
        stopCapture { stopped.complete(Unit) }
        withTimeoutOrNull(stopAwaitTimeoutMs) { stopped.await() }
    }

    override fun resumeAfterTx() {
        if (isOperating() && !isCapturing()) beginCapture()
    }

    companion object {
        /**
         * Generous for a healthy stop (AudioRecord stop/release + 500 ms join)
         * yet small against the 12.6 s FT8 waveform in a 15 s slot.
         */
        const val STOP_AWAIT_TIMEOUT_MS_DEFAULT = 1_500L
    }
}
