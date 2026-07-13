package net.packrig.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwo() {
        Fft(48)
    }

    // A real cosine at bin k should produce magnitude peaks at bins k and N-k.
    @Test
    fun cosinePeaksAtExpectedBin() {
        val n = 64
        val k = 5
        val re = DoubleArray(n) { Math.cos(2.0 * Math.PI * k * it / n) }
        val im = DoubleArray(n)

        Fft(n).transform(re, im)

        val mag = DoubleArray(n) { Math.hypot(re[it], im[it]) }
        val peak = mag.indices.maxByOrNull { mag[it] } ?: -1
        assertTrue("peak should be at bin k or N-k", peak == k || peak == n - k)

        // The peak should dominate a clearly non-signal bin.
        assertTrue(mag[k] > 10.0 * mag[k + 2])
    }

    @Test
    fun dcInputConcentratesInBinZero() {
        val n = 32
        val re = DoubleArray(n) { 1.0 }
        val im = DoubleArray(n)

        Fft(n).transform(re, im)

        assertEquals(n.toDouble(), re[0], 1e-9)
        for (i in 1 until n) {
            assertEquals(0.0, Math.hypot(re[i], im[i]), 1e-9)
        }
    }
}
