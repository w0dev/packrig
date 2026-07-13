package net.packrig.core

/**
 * Holds an operator-applied correction between the raw device clock and FT8
 * band time. [now] is the corrected epoch-ms that all slot timing reads.
 *
 * The correction is applied as a *residual*: each apply adds whatever error
 * remains on top of the current offset, so repeated applies converge toward
 * zero measured DT bias. Sign follows [ClockOffsetEstimator]: a positive
 * residual means a fast phone clock, and rolling [now] back by that amount
 * re-centres decode DT on the nominal signal-start.
 *
 * In-memory only; a fresh process starts at zero. [now] is read on the audio
 * capture thread while apply/reset run on Main, so [offsetMs] is @Volatile.
 */
class ClockCorrection(private val rawClock: () -> Long = { System.currentTimeMillis() }) {

    @Volatile
    private var offsetMs: Long = 0L

    fun now(): Long = rawClock() - offsetMs

    val appliedOffsetMs: Long get() = offsetMs

    /** Apply the remaining residual (seconds, signed) on top of the current correction. */
    fun applyResidualSeconds(residualSeconds: Float) {
        offsetMs += Math.round(residualSeconds * 1000f)
    }

    fun reset() {
        offsetMs = 0L
    }
}
