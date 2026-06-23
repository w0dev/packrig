package net.ft8vc.core

/**
 * Accumulates PCM samples into 15-second UTC slots and flushes a completed slot
 * for decoding when the wall clock crosses into the next slot.
 *
 * Pure logic (time is injected via [add]) so it can be unit-tested. Threading and
 * the actual decode call live in the caller.
 */
class SlotCollector(
    private val sampleRate: Int = AppInfo.SAMPLE_RATE_HZ,
    /** Minimum fraction of a slot that must be captured before we bother decoding. */
    minSlotFraction: Float = 0.85f,
) {
    private val maxSamples = (sampleRate * (AppInfo.SLOT_SECONDS + 1))
    private val minSamples = (sampleRate * AppInfo.SLOT_SECONDS * minSlotFraction).toInt()

    private val buffer = ShortArray(maxSamples)
    private var count = 0
    private var currentSlotStart = -1L

    /**
     * Append [frames] captured at [nowMillisUtc]. If this crosses a slot boundary,
     * [onSlot] is invoked once with the samples collected for the slot that just
     * ended (only if enough were captured).
     */
    fun add(frames: ShortArray, nowMillisUtc: Long, onSlot: (ShortArray, Long) -> Unit) {
        val slotStart = SlotTiming.slotStart(nowMillisUtc)
        if (currentSlotStart == -1L) {
            currentSlotStart = slotStart
        } else if (slotStart != currentSlotStart) {
            if (count >= minSamples) {
                onSlot(buffer.copyOf(count), currentSlotStart)
            }
            count = 0
            currentSlotStart = slotStart
        }

        val room = maxSamples - count
        val n = minOf(room, frames.size)
        if (n > 0) {
            System.arraycopy(frames, 0, buffer, count, n)
            count += n
        }
    }

    /**
     * Defensive copy of the in-progress slot buffer. Returns `null` if no
     * samples have been accumulated yet for the current slot.
     *
     * Used by the early-decode scheduler in DecodeController to run a
     * partial-slot decode pass without disturbing the boundary-driven
     * `onSlot` flow. Mutating the returned array MUST NOT affect a
     * subsequent `add(...)` or `onSlot` invocation.
     */
    fun snapshot(): ShortArray? = if (count > 0) buffer.copyOf(count) else null

    /** Discard the in-progress slot (e.g. when capture stops). */
    fun reset() {
        count = 0
        currentSlotStart = -1L
    }
}
