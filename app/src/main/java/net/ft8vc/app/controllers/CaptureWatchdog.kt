package net.ft8vc.app.controllers

/**
 * Detects a silent RX capture stall — no frames arriving while we believe we are
 * capturing — and decides when to recover or give up. Pure state machine (time
 * injected) so the debounce / grace / retry-cap logic is unit-testable.
 *
 * Threading: [onFrame] is called on the audio capture thread; [poll],
 * [onCaptureStarted] and [reset] run on the ViewModel (main) coroutine. Only
 * [lastFrameAtMs] is written from both, so it is @Volatile; the counters are
 * touched solely by the main-thread methods.
 */
class CaptureWatchdog(
    private val stallThresholdMs: Long = STALL_THRESHOLD_MS,
    private val restartGraceMs: Long = RESTART_GRACE_MS,
    private val maxRestarts: Int = MAX_RESTARTS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var lastFrameAtMs: Long = clock()
    private var restartCount: Int = 0
    private var graceUntilMs: Long = 0L

    /** Audio-thread: a frame arrived. */
    fun onFrame() {
        lastFrameAtMs = clock()
    }

    /** Main: capture (re)started — stamp fresh and open a spin-up grace window. Does NOT clear restartCount. */
    fun onCaptureStarted() {
        val now = clock()
        lastFrameAtMs = now
        graceUntilMs = now + restartGraceMs
    }

    /** Main: capture fully stopped — clear all state. */
    fun reset() {
        lastFrameAtMs = clock()
        restartCount = 0
        graceUntilMs = 0L
    }

    /** Main: monitor tick. May mutate the restart counter. */
    fun poll(isCapturing: Boolean, isTransmitting: Boolean, devicePresent: Boolean): Decision {
        if (!isCapturing || isTransmitting || !devicePresent) return Decision.Idle
        val now = clock()
        val stalled = now - lastFrameAtMs >= stallThresholdMs
        if (!stalled) {
            if (restartCount > 0 && now >= graceUntilMs) restartCount = 0
            return Decision.Idle
        }
        if (now < graceUntilMs) return Decision.Idle          // fresh restart still spinning up
        if (restartCount >= maxRestarts) return Decision.GiveUp
        restartCount += 1
        return Decision.Recover
    }

    sealed interface Decision {
        object Idle : Decision
        object Recover : Decision
        object GiveUp : Decision
    }

    companion object {
        const val STALL_THRESHOLD_MS = 3_000L
        const val RESTART_GRACE_MS = 3_000L
        const val MAX_RESTARTS = 3
    }
}
