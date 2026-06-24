package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SnrEstimatorTest {

    private val rate = 12_000
    private val symPeriod = 0.16
    private val nSpsym = 1920          // rate * symPeriod
    private val nSym = 79

    /** Build a single-tone FT8-length slot: a pure carrier at f0 for all 79 symbols,
     *  plus white noise of the given amplitude. Tone amplitude fixed at `amp`. */
    private fun synth(f0: Double, amp: Double, noise: Double, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        val total = nSym * nSpsym
        val out = ShortArray(total)
        for (n in 0 until total) {
            val t = n.toDouble() / rate
            val s = amp * sin(2 * PI * f0 * t) + noise * (rnd.nextDouble() * 2 - 1)
            out[n] = (s * 16000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    @Test
    fun spreadIsGainInvariant() {
        // Same signal scaled by 2x must yield (nearly) the same spread in dB,
        // because both max and min cells shift by the same +6 dB. Noise is kept
        // well above the 16-bit quantization floor so the floor cell scales with
        // gain rather than being pinned by quantization.
        val quiet = synth(1000.0, amp = 0.3, noise = 0.01)
        val loud = synth(1000.0, amp = 0.6, noise = 0.02)   // 2x signal AND 2x noise
        val a = SnrEstimator.spreadDb(quiet, rate, 1000f, 0f)
        val b = SnrEstimator.spreadDb(loud, rate, 1000f, 0f)
        assertEquals("spread must be invariant to overall gain", a, b, 0.5)
    }

    @Test
    fun moreNoiseLowersSpread() {
        val clean = SnrEstimator.spreadDb(synth(1000.0, 0.5, 0.0005), rate, 1000f, 0f)
        val noisy = SnrEstimator.spreadDb(synth(1000.0, 0.5, 0.05), rate, 1000f, 0f)
        assertTrue("more noise must reduce the signal-to-floor spread ($noisy !< $clean)", noisy < clean)
    }

    @Test
    fun estimateClampsToWsjtxRange() {
        // A blisteringly strong, clean tone must clamp at +24, not overflow.
        val strong = synth(1500.0, amp = 0.9, noise = 0.00001)
        val snr = SnrEstimator.estimate(strong, rate, 1500f, 0f, offsetDb = 0.0)
        assertTrue("clean strong tone should be high and clamped", snr in 20..24)
    }

    @Test
    fun emptyWindowsYieldFloor() {
        // dt far past the buffer → no symbol windows fit → floor value.
        val s = synth(1000.0, 0.5, 0.001)
        assertEquals(-24, SnrEstimator.estimate(s, rate, 1000f, 999f))
    }
}
