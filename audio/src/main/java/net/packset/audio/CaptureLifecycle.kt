package net.packset.audio

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes [AudioEngine] start/stop onto a dedicated single thread so the
 * AudioRecord framework calls (open, stop, release, thread join) — which can
 * block for seconds on a wedged or detaching USB device — never run on the
 * caller's (main) thread. Field ANR vector, 2026-07-03.
 *
 * Ordering guarantee: ops execute strictly in submission order, so
 * stop-then-start sequences (device swap, RELY-02/03 restart, TX pause/resume)
 * behave exactly as the previous synchronous calls did.
 */
class CaptureLifecycle(private val engine: AudioEngine) {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "packset-capture-lifecycle") }

    /**
     * Bumped on every start/stop submission. A failing start only reports via
     * its callback while it is still the newest op — a correction landing after
     * a subsequent stop/start would stomp the newer session's state.
     */
    private val opSeq = AtomicLong(0)

    /**
     * Queue an engine start. [onStartFailed] is invoked on the lifecycle thread
     * if the engine throws and no newer start/stop has been issued since.
     */
    fun start(preferredDeviceId: Int?, onFrames: (ShortArray) -> Unit, onStartFailed: (Throwable) -> Unit) {
        val op = opSeq.incrementAndGet()
        submit {
            try {
                engine.start(preferredDeviceId, onFrames)
            } catch (t: Throwable) {
                if (opSeq.get() == op) onStartFailed(t)
            }
        }
    }

    /**
     * Queue an engine stop. [onStopped] runs on the lifecycle thread after the
     * engine has actually stopped — the point where decode state may be reset
     * without racing the capture thread.
     */
    fun stop(onStopped: () -> Unit = {}) {
        opSeq.incrementAndGet()
        submit {
            engine.stop()
            onStopped()
        }
    }

    /** Queue a final engine stop and retire the lifecycle thread. */
    fun close() {
        opSeq.incrementAndGet()
        submit { engine.stop() }
        executor.shutdown()
    }

    fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        executor.awaitTermination(timeout, unit)

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Ops after close() are dropped; the close-time stop already ran or will run.
        }
    }
}
