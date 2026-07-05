package net.ft8vc.ft8native

import net.ft8vc.core.SnrEstimator
import net.ft8vc.core.WavData
import net.ft8vc.core.WavIo
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Host regression for the POTACAT-style [SnrEstimator] against WSJT-X ground
 * truth on the `210703_133430.wav` sample. Pure JVM — no device, no ft8_lib
 * decode: we feed WSJT-X's published frequencies directly (the estimator is
 * alignment-free).
 *
 * The estimator is directionally correct, not WSJT-X-exact (see the audit
 * report), so we assert the properties that matter operationally: positive slope
 * vs WSJT-X, strong signals read positive, and weak signals read mostly negative.
 */
class SnrEstimatorWavTest {

    private data class Dec(val freq: Float, val snr: Int)

    private fun loadFixtures(): Pair<WavData, List<Dec>>? {
        val bases = listOf("src/androidTest/assets/snr", "ft8-native/src/androidTest/assets/snr")
        for (b in bases) {
            val wav = File("$b/210703_133430.wav")
            val exp = File("$b/210703_133430.expected.txt")
            if (wav.exists() && exp.exists()) {
                val decs = exp.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                    val freq = line.substringBefore(' ')
                    val snr = line.substringAfter(' ').trim().substringBefore(' ')
                    Dec(freq.toFloat(), snr.toInt())
                }
                return WavIo.readPcm16(wav.readBytes()) to decs
            }
        }
        return null
    }

    @Test
    fun snrIsDirectionallyCorrectVsWsjtx() {
        val fx = loadFixtures()
        assumeTrue("WAV fixtures not found; skipping", fx != null)
        val (wav, decs) = fx!!

        val ours = decs.map { SnrEstimator.estimate(wav.samples, wav.sampleRate, it.freq) }
        val wsjtx = decs.map { it.snr.toDouble() }

        // Least-squares slope of ours vs WSJT-X — must be clearly positive (the old
        // score*0.5 had ~zero correlation; a flat/inverted estimator would fail).
        val mx = wsjtx.average()
        val my = ours.map { it.toDouble() }.average()
        val num = ours.indices.sumOf { (wsjtx[it] - mx) * (ours[it] - my) }
        val den = wsjtx.sumOf { (it - mx) * (it - mx) }
        val slope = num / den
        assertTrue("slope vs WSJT-X must be clearly positive, got $slope", slope > 0.6)

        // Strong signals (WSJT-X >= +14) must read clearly positive.
        decs.indices.filter { decs[it].snr >= 14 }.forEach {
            assertTrue("strong signal ${decs[it].freq}Hz read ${ours[it]}", ours[it] >= 5)
        }

        // Weak signals (WSJT-X <= -13) must read mostly negative (a few overlap
        // cases read high — that is the documented limitation).
        val weak = decs.indices.filter { decs[it].snr <= -13 }
        val weakNeg = weak.count { ours[it] < 0 }
        assertTrue(
            "most weak signals must read negative ($weakNeg/${weak.size})",
            weakNeg >= (weak.size * 7 + 9) / 10, // >= 70%
        )
    }

    @Test
    fun earlyTruncatedBufferMatchesFullSlot() {
        // The early decode pass estimates SNR from a ~12 s snapshot; the full
        // pass from the whole 15 s slot. Field report 2026-07-04: the two
        // disagreed for the same decode. On real air data they must agree.
        val fx = loadFixtures()
        assumeTrue("WAV fixtures not found; skipping", fx != null)
        val (wav, decs) = fx!!

        val n12 = wav.sampleRate * 12
        for (d in decs) {
            val full = SnrEstimator.estimate(wav.samples, wav.sampleRate, d.freq)
            val early = SnrEstimator.estimate(wav.samples.copyOf(n12), wav.sampleRate, d.freq)
            assertTrue(
                "${d.freq} Hz: early 12 s snapshot read $early, full slot $full",
                early == full,
            )
        }
    }
}
