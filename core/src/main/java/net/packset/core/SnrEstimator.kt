package net.packset.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * FT8 SNR estimator, ported from POTACAT (`lib/ft8-worker.js`).
 *
 * ft8_lib's decoder reports a Costas sync score, not an SNR; emitting
 * `score * 0.5` produced the bogus always-positive readout this replaces. POTACAT
 * independently hit the same bug and fixed it with the method used here.
 *
 * For each decoded candidate at audio frequency [freqHz], signal power is the
 * mean of the eight 8-FSK tone-bin powers (`f0 + k·6.25 Hz`) measured by a
 * Goertzel detector; the noise floor is the median bin power across the band
 * (200–3000 Hz). `SNR = 10·log10(signal / noise) + CALIBRATION_DB`, clamped to
 * WSJT-X's `[-24, +24]` range.
 *
 * All measurements are windowed to `[0.5 s, 12 s]` of the buffer (clamped to
 * the buffer end) rather than the whole buffer: the early decode pass runs on
 * a ~12 s snapshot of the slot while the full pass sees all 15 s, and Goertzel
 * power over differing buffer lengths gave the two passes different SNRs for
 * the same decode (field report 2026-07-04). With the shared window the usual
 * 12 s snapshot and the full slot produce identical estimates by construction,
 * and slot-tail energy after FT8's 13.1 s signal extent cannot skew the full
 * pass. Verified against WSJT-X ground truth on `210703_133430.wav`: mean
 * error 0.0 dB with the existing [CALIBRATION_DB], slope unchanged (≈0.80).
 *
 * The method is alignment-free (no time offset needed) and gain-invariant (the
 * signal/noise ratio cancels overall level). It is directionally correct and
 * mostly tracks WSJT-X within a few dB; it is NOT WSJT-X-exact — overlapping
 * signals within ~50 Hz can read high, and the dynamic range is mildly
 * compressed (measured slope ≈0.82 against WSJT-X). See the audit report.
 */
object SnrEstimator {

    private const val TONE_SPACING = 6.25
    private const val NUM_TONES = 8
    private const val NOISE_LO_HZ = 200
    private const val NOISE_HI_HZ = 3000
    // Bin spacing for the noise-floor median. The median is robust to bin count,
    // so 100 Hz (28 bins) tracks 50 Hz within noise while halving the per-slot
    // Goertzel cost.
    private const val NOISE_STEP_HZ = 100

    // Analysis window, seconds from slot start: skip the 0.5 s pre-signal gap,
    // stop at 12 s — the largest extent both the early snapshot and the full
    // slot buffer are guaranteed to share. Signal and noise must use the same
    // window or their Goertzel powers scale differently with length.
    private const val WINDOW_START_S = 0.5
    private const val WINDOW_END_S = 12.0

    /**
     * dB correction folding the 2500 Hz-bandwidth reference (POTACAT's
     * theoretical `10·log10(50/2500) = -17 dB`) plus an empirical term for the
     * in-band leakage bias. Mean-centered against WSJT-X sample
     * `210703_133430.wav`; refine with more sample WAVs.
     */
    const val CALIBRATION_DB: Double = -20.3

    /** Median band-bin power (the noise floor), computed once per slot. */
    fun noiseFloorPower(samples: ShortArray, sampleRate: Int): Double {
        val powers = ArrayList<Double>()
        var f = NOISE_LO_HZ
        while (f < NOISE_HI_HZ) {
            powers.add(goertzelPower(samples, f.toDouble(), sampleRate))
            f += NOISE_STEP_HZ
        }
        return median(powers)
    }

    /** SNR in dB for a candidate at [freqHz], given a precomputed [noiseFloorPower]. */
    fun estimate(samples: ShortArray, sampleRate: Int, freqHz: Float, noiseFloorPower: Double): Int {
        if (noiseFloorPower <= 0.0) return -24
        var sig = 0.0
        for (t in 0 until NUM_TONES) sig += goertzelPower(samples, freqHz + t * TONE_SPACING, sampleRate)
        sig /= NUM_TONES
        if (sig <= 0.0) return -24
        return (10.0 * log10(sig / noiseFloorPower) + CALIBRATION_DB).roundToInt().coerceIn(-24, 24)
    }

    /** Convenience for single-shot/tests: computes the noise floor internally. */
    fun estimate(samples: ShortArray, sampleRate: Int, freqHz: Float): Int =
        estimate(samples, sampleRate, freqHz, noiseFloorPower(samples, sampleRate))

    /** Goertzel power at [freq] over the shared analysis window of [samples]. */
    private fun goertzelPower(samples: ShortArray, freq: Double, sampleRate: Int): Double {
        val to = minOf(samples.size, (WINDOW_END_S * sampleRate).toInt())
        val start = (WINDOW_START_S * sampleRate).toInt()
        val from = if (start < to) start else 0
        val w = 2.0 * PI * freq / sampleRate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (n in from until to) {
            val s0 = samples[n].toDouble() + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val n = s.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
