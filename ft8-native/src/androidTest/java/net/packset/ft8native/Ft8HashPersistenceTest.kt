package net.packset.ft8native

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.packset.core.WavIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-slot hashed-callsign resolution (spec §1). Encode and decode share the
 * global table, so every scenario starts from clearCallsignTable().
 */
@RunWith(AndroidJUnit4::class)
class Ft8HashPersistenceTest {

    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    @Before
    fun requireNative() {
        assumeTrue(Ft8Native.isAvailable())
    }

    /** Encode both slots FIRST (encoding seeds the table), then clear before decoding. */
    private fun buildSlots(): Pair<ShortArray, ShortArray> {
        Ft8Native.clearCallsignTable()
        val cqSlot = Ft8Native.encode("CQ PJ4/K1ABC EM26", 1000f)
        val hashedSlot = Ft8Native.encode("PJ4/K1ABC W0DEV -10", 1500f)
        assertTrue("encode CQ failed", cqSlot.isNotEmpty())
        assertTrue("encode directed failed", hashedSlot.isNotEmpty())
        return cqSlot to hashedSlot
    }

    /** Mirror Ft8SnrCalibrationTest's asset/WavIo loading code. */
    private fun loadCalibrationWav(): ShortArray {
        val wav = WavIo.readPcm16(assets.open("snr/210703_133430.wav").use { it.readBytes() })
        return wav.samples
    }

    @Test
    fun hashedFormUnresolvedWithoutPriorSlot() {
        val (_, hashedSlot) = buildSlots()
        Ft8Native.clearCallsignTable()
        val decoded = Ft8Native.decode(hashedSlot)
        assertEquals(1, decoded.size)
        // Without the CQ slot's table entry the nonstandard call shows hashed as <...>.
        assertTrue(
            "expected unresolved <...> form, got: ${decoded[0].message}",
            decoded[0].message.contains("<...>"),
        )
    }

    @Test
    fun hashedFormResolvesFromEarlierSlot() {
        val (cqSlot, hashedSlot) = buildSlots()
        Ft8Native.clearCallsignTable()
        val first = Ft8Native.decode(cqSlot)
        assertTrue(
            "CQ slot must decode the full call: ${first.joinToString { it.message }}",
            first.any { it.message.contains("PJ4/K1ABC") },
        )
        val second = Ft8Native.decode(hashedSlot)
        assertEquals(1, second.size)
        // ft8_lib wraps RESOLVED hashed calls in angle brackets: <PJ4/K1ABC>.
        // Unresolved shows as <...>. So resolved means contains the call but NOT <...>.
        assertTrue(
            "expected resolved call (not <...>) from earlier slot, got: ${second[0].message}",
            second[0].message.contains("PJ4/K1ABC") && !second[0].message.contains("<...>"),
        )
    }

    /**
     * Spec §3 validation gate for ClockOffsetEstimator.NOMINAL_DT_S: encoded
     * slots place the waveform centered via silence padding — the DT the
     * decoder reports for our own encode output tells us the buffer-relative
     * nominal. The estimator's NOMINAL_DT_S is 0.68f = physical 0.5 s nominal
     * + the +0.18 s DT-axis bias this file measures: (15 s - 12.64 s) / 2 =
     * 1.18 s pad → expected DT ≈ 1.18 for encode output; axisBias = 1.18 −
     * 1.18 ≈ +0.18 s; NOMINAL_DT_S = 0.5 + 0.18 = 0.68 s. This test
     * documents the encode-buffer DT and sanity-checks the decoder's DT axis;
     * the live confirmation is field gate (iii).
     */
    @Test
    fun decoderDtAxisMatchesEncodePadding() {
        Ft8Native.clearCallsignTable()
        val slot = Ft8Native.encode("CQ W0DEV EM26", 1200f)
        val decoded = Ft8Native.decode(slot)
        assertEquals(1, decoded.size)
        val dt = decoded[0].dtSeconds
        assertTrue("encode-buffer DT expected ≈1.18 s, got $dt", dt > 0.9f && dt < 1.5f)
    }

    /**
     * Spec §3: NOMINAL_DT_S validation, self-calibrating against the encoder's
     * known DT axis. The encoder pads silence so the waveform is centred in a
     * 15 s slot: (15.0 − 12.64) / 2 = 1.18 s from buffer start. By encoding a
     * known message and decoding it we measure the decoder's reported DT for
     * that exact geometry (encodeDt), then derive the axis bias relative to the
     * theoretical 1.18 s (axisBias = encodeDt − 1.18). The WSJT-X calibration
     * WAV is slot-boundary aligned with signals that nominally start ~0.5 s
     * after the boundary, so on the decoder's axis the expected median is
     * nominalOnAxis = 0.5 + axisBias. We then assert the WAV median is within
     * 0.45 s of nominalOnAxis. If this fails, report BLOCKED with all measured
     * values — do NOT loosen further; correct NOMINAL_DT_S instead.
     */
    @Test
    fun calibrationWavMedianDtNearNominal() {
        assumeTrue(
            "snr/210703_133430.wav not present",
            runCatching { assets.open("snr/210703_133430.wav").close() }.isSuccess,
        )
        // Step 1: measure the decoder's DT axis using the known encode geometry.
        Ft8Native.clearCallsignTable()
        val encodeSlot = Ft8Native.encode("CQ W0DEV EM26", 1200f)
        val encodeDecoded = Ft8Native.decode(encodeSlot)
        assertEquals("encode/decode of CQ W0DEV EM26 must yield 1 decode", 1, encodeDecoded.size)
        val encodeDt = encodeDecoded[0].dtSeconds
        // Theoretical waveform start in the encode buffer: (15.0 - 12.64) / 2 = 1.18 s.
        val axisBias = encodeDt - 1.18f
        // Live-capture signals nominally start 0.5 s after the slot boundary.
        val nominalOnAxis = 0.5f + axisBias

        // Step 2: decode the calibration WAV and compute its median DT.
        Ft8Native.clearCallsignTable()
        val samples = loadCalibrationWav()
        val decoded = Ft8Native.decode(samples)
        assertTrue("calibration WAV must decode signals", decoded.size >= 4)
        val dts = decoded.map { it.dtSeconds }.sorted()
        // Upper-middle element for even N; adequate for a spread check.
        val median = dts[dts.size / 2]

        Log.i(
            "Ft8HashPersistenceTest",
            "CALIBRATION_VALUES encodeDt=$encodeDt axisBias=$axisBias " +
                "nominalOnAxis=$nominalOnAxis median=$median sortedDts=$dts",
        )
        assertTrue(
            "median DT $median not within 0.45 s of nominalOnAxis $nominalOnAxis " +
                "(encodeDt=$encodeDt axisBias=$axisBias nominalOnAxis=$nominalOnAxis " +
                "median=$median sortedDts=$dts)",
            kotlin.math.abs(median - nominalOnAxis) <= 0.45f,
        )
    }
}
