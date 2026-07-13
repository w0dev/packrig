package net.packrig.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirDecimatorTest {

    private fun tone(freqHz: Double, rate: Int, n: Int, amp: Double = 10000.0): ShortArray =
        ShortArray(n) { (amp * Math.sin(2.0 * Math.PI * freqHz * it / rate)).toInt().toShort() }

    private fun rms(x: ShortArray, skip: Int): Double {
        var acc = 0.0
        var count = 0
        for (i in skip until x.size) {
            acc += x[i].toDouble() * x[i].toDouble()
            count++
        }
        return if (count == 0) 0.0 else Math.sqrt(acc / count)
    }

    @Test
    fun outputRateIsInputOverFactor() {
        val dec = FirDecimator.lowPass(inputRate = 48000, factor = 4)
        val out = dec.process(tone(500.0, 48000, 4096))
        // Allow +/- a couple samples of edge effect from streaming bookkeeping.
        assertEquals(1024.0, out.size.toDouble(), 2.0)
    }

    // A tone well inside the passband survives; a tone above output Nyquist
    // (which would alias) is strongly attenuated.
    @Test
    fun attenuatesAliasingTone() {
        val rate = 48000
        val n = 8192

        val low = FirDecimator.lowPass(rate, 4).process(tone(500.0, rate, n))
        val high = FirDecimator.lowPass(rate, 4).process(tone(8000.0, rate, n))

        val skip = 64 // let the FIR settle
        val lowRms = rms(low, skip)
        val highRms = rms(high, skip)

        assertTrue("passband tone should retain energy", lowRms > 3000.0)
        assertTrue("aliasing tone should be heavily attenuated", highRms < lowRms / 10.0)
    }
}
