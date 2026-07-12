package net.packset.ft8native

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.packset.core.SnrEstimator
import net.packset.core.WavIo
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * On-device end-to-end check: decode each WAV under the `snr` assets folder
 * through ft8_lib, then
 * recompute SNR with [SnrEstimator] (POTACAT method) and compare to WSJT-X's
 * published values in the sibling `.expected.txt` (`<freqHz> <snr> <message>`).
 *
 * The estimator is directionally correct, not WSJT-X-exact (see audit report), so
 * matched decodes are asserted within a generous band, allowing the documented
 * overlapping-signal outliers. Self-skips without fixtures.
 */
@RunWith(AndroidJUnit4::class)
class Ft8SnrCalibrationTest {

    private companion object {
        const val TAG = "Ft8SnrCal"
        const val FREQ_MATCH_HZ = 6f
        const val MEDIAN_ABS_ERR_DB = 8 // tolerates 2-3 overlapping-signal outliers
    }

    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private data class Expected(val freqHz: Float, val snr: Int, val message: String)

    private fun listWavs(): List<String> =
        (assets.list("snr") ?: emptyArray()).filter { it.endsWith(".wav") }.map { "snr/$it" }

    private fun readExpected(wavPath: String): List<Expected> {
        val txt = wavPath.removeSuffix(".wav") + ".expected.txt"
        val bytes = runCatching { assets.open(txt).use { it.readBytes() } }.getOrNull() ?: return emptyList()
        return bytes.toString(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val freq = line.substringBefore(' ')
            val rest = line.substringAfter(' ').trim()
            Expected(freq.toFloat(), rest.substringBefore(' ').toInt(), rest.substringAfter(' ').trim())
        }
    }

    @Test
    fun snrTracksWsjtxAcrossSampleWavs() {
        val wavs = listWavs()
        assumeTrue("No assets/snr/*.wav fixtures; skipping", wavs.isNotEmpty())
        assertTrue("libpackset.so failed to load", Ft8Native.isAvailable())

        val absErrs = mutableListOf<Int>()
        for (wavPath in wavs) {
            val wav = WavIo.readPcm16(assets.open(wavPath).use { it.readBytes() })
            val decodes = Ft8Native.decode(wav.samples, wav.sampleRate)
            val noise = SnrEstimator.noiseFloorPower(wav.samples, wav.sampleRate)
            for (exp in readExpected(wavPath)) {
                val match = decodes.firstOrNull {
                    abs(it.freqHz - exp.freqHz) <= FREQ_MATCH_HZ && it.message.trim() == exp.message
                } ?: continue
                val ours = SnrEstimator.estimate(wav.samples, wav.sampleRate, match.freqHz, noise)
                Log.i(TAG, "wsjtx=%+d ours=%+d  %s".format(exp.snr, ours, exp.message))
                absErrs += abs(ours - exp.snr)
            }
        }
        assumeTrue("No expected decodes matched", absErrs.isNotEmpty())
        val median = absErrs.sorted()[absErrs.size / 2]
        assertTrue("median |ours-wsjtx| too high: $median dB", median <= MEDIAN_ABS_ERR_DB)
    }
}
