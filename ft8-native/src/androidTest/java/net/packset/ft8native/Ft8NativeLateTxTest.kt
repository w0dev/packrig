package net.packset.ft8native

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Ft8NativeLateTxTest {

    private companion object {
        const val MSG = "CQ TEST FN42"
        const val FREQ = 1500.0f
        const val RATE = 12_000
        const val FT8_NN = 79
        const val NSPSYM = 1920
        const val WAVEFORM_START_SAMPLES = 14_160  // (180_000 - 79*1920) / 2
    }

    @Before fun nativeLoaded() {
        assumeTrue("libft8vc.so not loaded", Ft8Native.isAvailable())
    }

    @Test
    fun encodeOffsetZeroMatchesV1Path() {
        // Calling with explicit offsetSymbols=0 must produce the same buffer as the
        // pre-parameter encode would have produced (regression guard).
        val baseline = Ft8Native.encode(MSG, FREQ, RATE)            // defaulted to 0
        val explicit = Ft8Native.encode(MSG, FREQ, RATE, 0)
        assertEquals(RATE * 15, baseline.size)
        assertArrayEquals(baseline, explicit)
    }

    @Test
    fun encodeOffsetThirteenReturnsTailOfWaveform() {
        val full = Ft8Native.encode(MSG, FREQ, RATE, 0)
        val truncated = Ft8Native.encode(MSG, FREQ, RATE, 13)
        val expectedSize = (FT8_NN - 13) * NSPSYM
        assertEquals(expectedSize, truncated.size)

        // The truncated buffer should contain the waveform for symbols [13, 79),
        // with similar energy/magnitude to the corresponding region in the full buffer,
        // but phase may differ due to independent synthesis (offset > 0 starts phi=0).
        // This test verifies that the offset mechanism works: the buffer is the right
        // size and contains non-trivial audio data with similar amplitude range.

        // Calculate RMS energy of truncated buffer
        val rmsTruncated = kotlin.math.sqrt(
            truncated.map { it.toDouble() * it }.average()
        )

        // Calculate RMS of the corresponding full-buffer region
        val tailStartInFull = WAVEFORM_START_SAMPLES + 13 * NSPSYM
        var sumSquares = 0.0
        for (i in 0 until expectedSize) {
            val sample = full[tailStartInFull + i].toDouble()
            sumSquares += sample * sample
        }
        val rmsFullTail = kotlin.math.sqrt(sumSquares / expectedSize)

        // Both should have similar energy (within a factor of 1.5x due to amplitude scaling
        // or phase effects), indicating they're both valid audio waveforms for the same content.
        assertTrue(
            "Truncated buffer is silent or trivial (RMS=$rmsTruncated)",
            rmsTruncated > 100  // Non-trivial audio (typical FT8 signal is ~5000-20000 PCM units)
        )
        assertTrue(
            "RMS mismatch suggests phase/envelope error: truncated=$rmsTruncated vs full=$rmsFullTail",
            rmsTruncated > rmsFullTail * 0.5 && rmsTruncated < rmsFullTail * 1.5
        )

        // Zero-lag normalized cross-correlation against the corresponding window of the
        // full v1.0 buffer. The truncated synthesis restarts at phi=0 rather than phase-
        // continuing from the v1.0 trajectory, so per-sample equality does not hold. But
        // the instantaneous-frequency trajectory for symbols [13, 79) is identical, so
        // a correct implementation correlates strongly (~0.9+) while a symbol-window
        // off-by-one or wrong-slice-direction drops correlation to near zero.
        var dotProduct = 0.0
        for (i in 0 until expectedSize) {
            dotProduct += truncated[i].toDouble() * full[tailStartInFull + i].toDouble()
        }
        // Measured correlation on Pixel emulator: 0.9996 (phase-reset effect is small because
        // symbol-boundary phase happens to be near-aligned for this message). Threshold 0.99
        // leaves comfortable margin for FP variance while catching any symbol-window error
        // (misalignment drops correlation to ~0.1 per the reviewer's analysis).
        val correlation = dotProduct / (rmsTruncated * rmsFullTail * expectedSize)
        assertTrue(
            "Zero-lag cross-correlation too low ($correlation); symbol window offset is likely wrong",
            correlation > 0.99
        )
    }

    @Test
    fun encodeOffsetMaxReturnsEmpty() {
        val empty = Ft8Native.encode(MSG, FREQ, RATE, FT8_NN)
        assertEquals(0, empty.size)
    }

    @Test
    fun encodeOffsetOneSymbolReturnsExpectedSize() {
        val one = Ft8Native.encode(MSG, FREQ, RATE, FT8_NN - 1)
        assertEquals(NSPSYM, one.size)
    }
}
