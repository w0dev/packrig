package net.packset.core

/**
 * Estimates the phone-clock offset versus FT8 band time from decode DTs.
 *
 * Live-capture buffers are slot-boundary aligned and FT8 transmissions
 * nominally begin [NOMINAL_DT_S] into the slot, so
 * `median(DT) - NOMINAL_DT_S` is the clock error: a FAST phone clock opens
 * the slot early, signals land late in the buffer, and the offset reads
 * positive. Only meaningful within the decoder's sync window (~±3 s) and
 * modulo the 15 s grid — a clock worse than that produces no decodes at all.
 *
 * Feed [onSlotDts] once per FULL decode pass (empty lists age the window so
 * a stale estimate expires after [WINDOW_SLOTS] quiet slots).
 */
class ClockOffsetEstimator {

    private val window = ArrayDeque<List<Float>>()

    fun onSlotDts(dts: List<Float>) {
        window.addLast(dts)
        while (window.size > WINDOW_SLOTS) window.removeFirst()
    }

    /** Signed offset in seconds, or null when fewer than [MIN_SAMPLES] recent DTs. */
    val offsetSeconds: Float?
        get() {
            val pooled = window.flatten()
            if (pooled.size < MIN_SAMPLES) return null
            return median(pooled) - NOMINAL_DT_S
        }

    fun reset() = window.clear()

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }

    companion object {
        const val WINDOW_SLOTS = 4
        const val MIN_SAMPLES = 4
        /** Nominal FT8 signal start (0.5 s) as read on this decoder's DT axis, which carries a measured +0.18 s bias (encode ground truth, see Ft8HashPersistenceTest). */
        const val NOMINAL_DT_S = 0.68f
        /** Show the chip (amber) at this absolute offset. */
        const val WARN_S = 1.0f
        /** Chip turns red at this absolute offset. */
        const val SEVERE_S = 2.0f
    }
}
