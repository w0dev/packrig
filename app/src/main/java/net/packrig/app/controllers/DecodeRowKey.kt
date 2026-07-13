package net.packrig.app.controllers

import kotlin.math.roundToLong

/**
 * Cross-pass-stable hash for [net.packrig.app.DecodeRow.id].
 *
 * The early-decode (t=12s) pass and the full-slot (t=15s) pass each call
 * the JNI separately and report slightly different `freqHz` for the same
 * logical decode (sub-bin jitter). Snapping `freqHz` to a 6.25 Hz grid
 * (one FT8 tone bin) and folding with the slot start + trimmed message
 * text produces a key that usually collides for the same logical decode
 * across both passes. When the true frequency sits near a bin boundary the
 * two passes can round to adjacent bins, so
 * [net.packrig.app.controllers.DecodeController]'s per-slot seenKeys dedup
 * matches via [candidateIds] (the nearest two bins) rather than a single
 * [stableId] lookup.
 */
object DecodeRowKey {
    /** One FT8 tone-bin = 6.25 Hz (12000 Hz / 2 / 960 baseband bins). */
    const val FREQ_BIN_HZ: Double = 6.25

    fun stableId(slotStartEpochMs: Long, freqHz: Double, message: String): Long =
        idForBin(slotStartEpochMs, (freqHz / FREQ_BIN_HZ).roundToLong(), message)

    /**
     * Ids for the two bins nearest [freqHz], nearest first (`[0]` equals
     * [stableId]). When the true frequency sits near a bin boundary, the
     * early and full passes can round to adjacent bins and their stableIds
     * split; a dedup that matches against both candidates cannot be split
     * by that sub-bin jitter.
     */
    fun candidateIds(slotStartEpochMs: Long, freqHz: Double, message: String): LongArray {
        val pos = freqHz / FREQ_BIN_HZ
        val nearest = pos.roundToLong()
        val other = if (nearest <= pos) nearest + 1 else nearest - 1
        return longArrayOf(
            idForBin(slotStartEpochMs, nearest, message),
            idForBin(slotStartEpochMs, other, message),
        )
    }

    private fun idForBin(slotStartEpochMs: Long, bin: Long, message: String): Long {
        // Mix slotStart, bin, and message hash into a 64-bit id with low
        // collision over a per-slot decode set (<= ~50 decodes/slot in practice).
        val msgHash = message.trim().hashCode().toLong() and 0xFFFFFFFFL
        val binHash = bin and 0xFFFFL
        val slotHash = slotStartEpochMs and 0x7FFFFFFFFFFFL  // 47 bits
        return (slotHash shl 17) xor (binHash shl 32) xor msgHash
    }
}
