package net.ft8vc.ft8native

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ft8vc.core.SnrEstimator
import net.ft8vc.core.WavIo
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Pins [SnrEstimator.DEFAULT_OFFSET_DB] and locks SNR parity with WSJT-X.
 *
 * Reads every WAV under the `snr` assets folder with a sibling `.expected.txt`
 * of `<freqHz> <wsjtxSnrDb> <message>` lines. For each expected decode it matches
 * our decode by frequency (within 5 Hz) and message, then:
 *   - logs (spreadDb, wsjtxSnr) so you can fit the offset (REPORT mode), and
 *   - asserts abs(ourSnr - wsjtxSnr) less-equal TOL_DB (regression).
 *
 * To calibrate: drop sample WAVs + expected files under the
 * `ft8-native/src/androidTest/assets/snr` folder, temporarily raise TOL_DB, run
 * on a device, read the `FIT` log lines, set DEFAULT_OFFSET_DB = mean(spread -
 * wsjtx), restore TOL_DB. Without fixtures the test self-skips.
 */
@RunWith(AndroidJUnit4::class)
class Ft8SnrCalibrationTest {

    private companion object {
        const val TAG = "Ft8SnrCal"
        const val TOL_DB = 3                 // operator-equivalent tolerance
        const val FREQ_MATCH_HZ = 5f
    }

    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private data class Expected(val freqHz: Float, val snr: Int, val message: String)

    private fun listWavs(): List<String> =
        (assets.list("snr") ?: emptyArray()).filter { it.endsWith(".wav") }.map { "snr/$it" }

    private fun readExpected(wavPath: String): List<Expected> {
        val txt = wavPath.removeSuffix(".wav") + ".expected.txt"
        val bytes = runCatching { assets.open(txt).use { it.readBytes() } }.getOrNull() ?: return emptyList()
        return bytes.toString(Charsets.UTF_8).lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { line ->
                val freq = line.substringBefore(' ')
                val rest = line.substringAfter(' ').trim()
                val snr = rest.substringBefore(' ')
                val msg = rest.substringAfter(' ').trim()
                Expected(freq.toFloat(), snr.toInt(), msg)
            }
    }

    @Test
    fun snrTracksWsjtxAcrossSampleWavs() {
        val wavs = listWavs()
        assumeTrue("No assets/snr/*.wav fixtures; skipping calibration", wavs.isNotEmpty())
        assertTrue("libft8vc.so failed to load", Ft8Native.isAvailable())

        var checked = 0
        for (wavPath in wavs) {
            val wav = WavIo.readPcm16(assets.open(wavPath).use { it.readBytes() })
            val decodes = Ft8Native.decode(wav.samples, wav.sampleRate)
            for (exp in readExpected(wavPath)) {
                val match = decodes.firstOrNull {
                    abs(it.freqHz - exp.freqHz) <= FREQ_MATCH_HZ && it.message.trim() == exp.message
                } ?: continue
                val spread = SnrEstimator.spreadDb(wav.samples, wav.sampleRate, match.freqHz, match.dtSeconds)
                val ours = SnrEstimator.estimate(wav.samples, wav.sampleRate, match.freqHz, match.dtSeconds)
                Log.i(TAG, "FIT spread=%.2f wsjtx=%+d ours=%+d  %s"
                    .format(spread, exp.snr, ours, exp.message))
                assertTrue(
                    "SNR off by >$TOL_DB dB for '${exp.message}': ours=$ours wsjtx=${exp.snr}",
                    abs(ours - exp.snr) <= TOL_DB,
                )
                checked++
            }
        }
        assumeTrue("No expected decodes matched; add *.expected.txt fixtures", checked > 0)
    }
}
