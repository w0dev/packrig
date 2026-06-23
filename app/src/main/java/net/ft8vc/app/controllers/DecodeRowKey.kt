package net.ft8vc.app.controllers

import kotlin.math.roundToLong

/**
 * Cross-pass-stable hash for [net.ft8vc.app.DecodeRow.id].
 *
 * The early-decode (t=12s) pass and the full-slot (t=15s) pass each call
 * the JNI separately and report slightly different `freqHz` for the same
 * logical decode (sub-bin jitter). Snapping `freqHz` to a 6.25 Hz grid
 * (one FT8 tone bin) and folding with the slot start + trimmed message
 * text produces a key that collides for the same logical decode across
 * both passes, so [net.ft8vc.app.controllers.DecodeController]'s per-slot
 * seenKeys set deduplicates them.
 */
object DecodeRowKey {
    /** One FT8 tone-bin = 6.25 Hz (12000 Hz / 2 / 960 baseband bins). */
    const val FREQ_BIN_HZ: Double = 6.25

    fun stableId(slotStartEpochMs: Long, freqHz: Double, message: String): Long {
        val bin = (freqHz / FREQ_BIN_HZ).roundToLong()
        // Mix slotStart, bin, and message hash into a 64-bit id with low
        // collision over a per-slot decode set (<= ~50 decodes/slot in practice).
        val msgHash = message.trim().hashCode().toLong() and 0xFFFFFFFFL
        val binHash = bin and 0xFFFFL
        val slotHash = slotStartEpochMs and 0x7FFFFFFFFFFFL  // 47 bits
        return (slotHash shl 17) xor (binHash shl 32) xor msgHash
    }
}
