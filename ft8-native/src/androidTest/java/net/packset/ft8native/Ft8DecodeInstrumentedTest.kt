package net.packset.ft8native

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.packset.core.WavIo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Golden-file decode regression test. Runs on a device/emulator (loads the native
 * library).
 *
 * Drop a 12 kHz mono WAV of a real FT8 slot at
 * `ft8-native/src/androidTest/assets/ft8_test.wav`. The test then asserts the
 * decoder finds at least one message. Without that asset the test self-skips, so
 * CI stays green until you add real capture data.
 *
 * Optionally add `ft8_test.expected.txt` (one expected message substring per line)
 * to assert specific decodes.
 */
@RunWith(AndroidJUnit4::class)
class Ft8DecodeInstrumentedTest {

    private companion object {
        const val TAG = "Ft8DecodeTest"
    }

    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun nativeLibraryLoads() {
        assertTrue("libft8vc.so failed to load", Ft8Native.isAvailable())
        assertNotNull(Ft8Native.version())
    }

    // Synthesize a message to PCM, decode it back, and assert it round-trips.
    // This validates the entire transmit waveform path without any radio.
    @Test
    fun encodeThenDecodeRoundTrips() {
        val message = "CQ W0DEV EM26"
        val pcm = Ft8Native.encode(message, freqHz = 1000f, sampleRate = 12_000)
        assertTrue("encode produced no audio", pcm.size > 12_000 * 14)

        val results = Ft8Native.decode(pcm, 12_000)
        Log.i(TAG, "Round-trip decoded ${results.size}: ${results.joinToString { it.message }}")
        assertTrue(
            "Round-trip should recover the transmitted message",
            results.any { it.message.trim() == message },
        )
    }

    @Test
    fun decodesGoldenWavIfPresent() {
        val bytes = try {
            assets.open("ft8_test.wav").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
        assumeTrue("No ft8_test.wav asset; skipping golden decode test", bytes != null)

        val wav = WavIo.readPcm16(bytes!!)
        Log.i(TAG, "Loaded WAV: ${wav.samples.size} samples @ ${wav.sampleRate} Hz " +
            "(${"%.1f".format(wav.samples.size.toFloat() / wav.sampleRate)} s)")

        val results = Ft8Native.decode(wav.samples, wav.sampleRate)

        // Decode dump for manual review against the golden clip.
        Log.i(TAG, "Decoded ${results.size} message(s):")
        Log.i(TAG, "  SNR    dT   Freq  Message")
        results.sortedByDescending { it.score }.forEach { r ->
            Log.i(TAG, "  %+3d %+5.1f %5d  %s".format(r.snr, r.dtSeconds, Math.round(r.freqHz), r.message))
        }

        assertTrue("Expected at least one decode from the golden clip", results.isNotEmpty())

        val expected = try {
            assets.open("ft8_test.expected.txt").use { it.readBytes() }
                .toString(Charsets.UTF_8)
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: IOException) {
            emptyList()
        }

        expected.forEach { needle ->
            assertTrue(
                "Expected a decode containing \"$needle\"",
                results.any { it.message.contains(needle) },
            )
        }
    }
}
