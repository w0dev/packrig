package net.ft8vc.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * FT8 SNR estimator, ported from PyFT8 (G1OJS) `receiver.py`.
 *
 * For a decoded candidate at audio frequency [freqHz] starting at [dtSeconds]
 * within the slot, we build a per-symbol power spectrum at the eight 8-FSK tone
 * bins (f0 + k·6.25 Hz, k=0..7) using a Goertzel detector over each symbol's
 * 1920-sample window, in dB. The reported SNR is the spread between the
 * strongest cell (the signal tone) and the weakest cell (the noise floor),
 * minus a fixed calibration [DEFAULT_OFFSET_DB] that references the result to
 * WSJT-X's 2500 Hz noise bandwidth, clamped to WSJT-X's [-24, +24] range.
 *
 * `maxCellDb - minCellDb` is invariant to overall input gain, so the offset is a
 * true fixed reference, not a level-dependent fudge.
 */
object SnrEstimator {

    private const val FT8_NUM_SYMBOLS = 79
    private const val FT8_SYMBOL_PERIOD = 0.16          // seconds
    private const val FT8_TONE_SPACING = 6.25           // Hz
    private const val FT8_NUM_TONES = 8

    /** Pinned against WSJT-X sample WAVs in the calibration test (Task 4). */
    const val DEFAULT_OFFSET_DB: Double = 0.0

    /** Offset-independent signal-to-floor spread in dB, or NaN if no window fits. */
    fun spreadDb(samples: ShortArray, sampleRate: Int, freqHz: Float, dtSeconds: Float): Double {
        val nSpsym = (0.5 + sampleRate * FT8_SYMBOL_PERIOD).toInt()
        var maxDb = Double.NEGATIVE_INFINITY
        var minDb = Double.POSITIVE_INFINITY
        var anyWindow = false

        for (sym in 0 until FT8_NUM_SYMBOLS) {
            val start = ((dtSeconds + sym * FT8_SYMBOL_PERIOD) * sampleRate).roundToInt()
            if (start < 0 || start + nSpsym > samples.size) continue
            anyWindow = true
            for (k in 0 until FT8_NUM_TONES) {
                val f = freqHz + k * FT8_TONE_SPACING
                val power = goertzelPower(samples, start, nSpsym, f, sampleRate)
                val db = 10.0 * log10(power.coerceAtLeast(1e-12))
                if (db > maxDb) maxDb = db
                if (db < minDb) minDb = db
            }
        }
        return if (anyWindow) maxDb - minDb else Double.NaN
    }

    /** Calibrated, clamped integer SNR in dB. Floors to -24 when no window fits. */
    fun estimate(
        samples: ShortArray,
        sampleRate: Int,
        freqHz: Float,
        dtSeconds: Float,
        offsetDb: Double = DEFAULT_OFFSET_DB,
    ): Int {
        val spread = spreadDb(samples, sampleRate, freqHz, dtSeconds)
        if (spread.isNaN()) return -24
        return (spread - offsetDb).roundToInt().coerceIn(-24, 24)
    }

    /** Goertzel power of [count] samples starting at [start] at frequency [freq]. */
    private fun goertzelPower(
        samples: ShortArray,
        start: Int,
        count: Int,
        freq: Double,
        sampleRate: Int,
    ): Double {
        val w = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (n in 0 until count) {
            val s0 = samples[start + n].toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
