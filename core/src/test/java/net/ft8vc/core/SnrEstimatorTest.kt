package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SnrEstimatorTest {

    private val rate = 12_000
    private val nSym = 79
    private val nSpsym = 1920

    /** A full FT8-length slot: a carrier at f0 plus white noise. */
    private fun synth(f0: Double, amp: Double, noise: Double, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        val total = nSym * nSpsym
        return ShortArray(total) { n ->
            val t = n.toDouble() / rate
            ((amp * sin(2 * PI * f0 * t) + noise * (rnd.nextDouble() * 2 - 1)) * 16000)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun gainInvariant() {
        // Scaling the whole signal must not change SNR: signal/noise cancels level.
        val quiet = synth(1000.0, amp = 0.3, noise = 0.02)
        val loud = synth(1000.0, amp = 0.6, noise = 0.04) // 2x everything
        assertEquals(
            SnrEstimator.estimate(quiet, rate, 1000f),
            SnrEstimator.estimate(loud, rate, 1000f),
        )
    }

    @Test
    fun moreNoiseLowersSnr() {
        val clean = SnrEstimator.estimate(synth(1000.0, 0.3, 0.02), rate, 1000f)
        val noisy = SnrEstimator.estimate(synth(1000.0, 0.3, 0.5), rate, 1000f)
        assertTrue("more noise must lower SNR ($noisy !< $clean)", noisy < clean)
    }

    @Test
    fun strongCleanToneReadsHighAndClamps() {
        val strong = synth(1500.0, amp = 0.9, noise = 0.0005)
        val snr = SnrEstimator.estimate(strong, rate, 1500f)
        assertTrue("clean strong tone should read high, got $snr", snr >= 10)
        assertTrue("must clamp at +24, got $snr", snr <= 24)
    }

    @Test
    fun silenceFloorsToMinus24() {
        // All-zero samples → zero signal and zero noise floor → floor value.
        assertEquals(-24, SnrEstimator.estimate(ShortArray(nSym * nSpsym), rate, 1000f))
    }

    /**
     * FT8-like continuous-phase 8-FSK: 79 random symbols × 0.16 s at
     * f0 + tone·6.25 Hz, starting 0.5 s into a [totalSeconds] buffer, plus
     * white noise. Mirrors the on-air timing the early/full decode passes see.
     */
    private fun synthFsk(
        f0: Double,
        amp: Double,
        noise: Double,
        seed: Int,
        totalSeconds: Double = 15.0,
    ): ShortArray {
        val rnd = Random(seed)
        val total = (totalSeconds * rate).toInt()
        val out = DoubleArray(total) { noise * (rnd.nextDouble() * 2 - 1) }
        var phase = 0.0
        var pos = rate / 2 // signal starts 0.5 s into the slot
        repeat(nSym) {
            val f = f0 + rnd.nextInt(8) * 6.25
            val w = 2 * PI * f / rate
            for (n in 0 until nSpsym) {
                if (pos + n >= total) break
                out[pos + n] += amp * sin(phase + w * n)
            }
            phase = (phase + w * nSpsym) % (2 * PI)
            pos += nSpsym
        }
        return ShortArray(total) { n ->
            (out[n] * 16000).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun earlyTruncatedBufferMatchesFullSlotEstimate() {
        // The early decode pass runs on a ~12 s snapshot of the same slot the
        // full pass sees at 15 s. Both must yield the same SNR for the signal.
        for (seed in 0 until 6) {
            val f0 = 800.0 + 150 * seed
            val full = synthFsk(f0, amp = 0.3, noise = 0.3, seed = seed)
            val truncated = full.copyOf(rate * 12)
            assertEquals(
                "seed=$seed f0=$f0: 12 s snapshot must match full-slot estimate",
                SnrEstimator.estimate(full, rate, f0.toFloat()),
                SnrEstimator.estimate(truncated, rate, f0.toFloat()),
            )
        }
    }

    @Test
    fun minimumEarlySnapshotAgreesWithinTolerance() {
        // Worst-case early snapshot (9.6 s, DecodeController's earlyMinSamples)
        // has genuinely seen ~20% fewer symbols than the 12 s analysis window,
        // so exact agreement is impossible — bound the disagreement instead.
        // The typical early pass fires at 12 s and matches the full slot exactly
        // (see earlyTruncatedBufferMatchesFullSlotEstimate).
        for (seed in 0 until 6) {
            val f0 = 800.0 + 150 * seed
            val full = synthFsk(f0, amp = 0.3, noise = 0.3, seed = seed)
            val short = full.copyOf((rate * 9.6).toInt())
            val d = SnrEstimator.estimate(full, rate, f0.toFloat()) -
                SnrEstimator.estimate(short, rate, f0.toFloat())
            assertTrue("seed=$seed f0=$f0: 9.6 s snapshot off by $d dB", d >= -4 && d <= 4)
        }
    }

    @Test
    fun energyAfterSignalExtentDoesNotAffectEstimate() {
        // FT8 transmissions end by ~13.1 s; a burst in the slot tail (e.g. a
        // tuner keyed late) must not change the estimate — the full pass would
        // read it, the early pass never sees it.
        val f0 = 1200.0
        val clean = synthFsk(f0, amp = 0.3, noise = 0.3, seed = 42)
        val withTailBurst = clean.copyOf()
        val burstStart = (rate * 13.3).toInt()
        for (n in burstStart until withTailBurst.size) {
            val t = (n - burstStart).toDouble() / rate
            val v = withTailBurst[n] + (16000 * 0.8 * sin(2 * PI * f0 * t)).toInt()
            withTailBurst[n] = v.coerceIn(-32768, 32767).toShort()
        }
        assertEquals(
            SnrEstimator.estimate(clean, rate, f0.toFloat()),
            SnrEstimator.estimate(withTailBurst, rate, f0.toFloat()),
        )
    }
}
