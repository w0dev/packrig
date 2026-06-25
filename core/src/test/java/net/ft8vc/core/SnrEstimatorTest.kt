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
}
