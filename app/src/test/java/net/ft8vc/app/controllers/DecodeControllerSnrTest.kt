package net.ft8vc.app.controllers

import net.ft8vc.core.SnrEstimator
import net.ft8vc.ft8native.Ft8DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The controller must report SnrEstimator's value, not the decoder's raw snr.
 * This pins the helper that maps decoder output → SNR-corrected output.
 */
class DecodeControllerSnrTest {

    @Test
    fun recomputesSnrFromSamplesNotDecoder() {
        val rate = 12_000
        // A 1000 Hz tone over a full slot, with a deliberately wrong decoder snr.
        val total = 79 * 1920
        val samples = ShortArray(total) { i ->
            (12000 * kotlin.math.sin(2 * Math.PI * 1000.0 * i / rate)).toInt().toShort()
        }
        val decoderOut = arrayOf(
            Ft8DecodeResult("CQ K1ABC FN42", snr = 99, dtSeconds = 0f, freqHz = 1000f, score = 40),
        )

        val corrected = DecodeController.withRecomputedSnr(decoderOut, samples, rate)

        val expected = SnrEstimator.estimate(samples, rate, 1000f)
        assertEquals(expected, corrected.single().snr)
        // Other fields preserved.
        assertEquals("CQ K1ABC FN42", corrected.single().message)
        assertEquals(40, corrected.single().score)
    }
}
