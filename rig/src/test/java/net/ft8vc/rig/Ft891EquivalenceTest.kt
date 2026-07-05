package net.ft8vc.rig

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-equivalence between the new parameterized YaesuCat(FT891) and the
 * legacy Ft891Cat it replaces. TEMPORARY: deleted together with Ft891Cat once
 * RigController is rewired (multi-rig phase 1, Task 9). Behavior parity on the
 * wire is the milestone bar.
 */
class Ft891EquivalenceTest {

    private val cat = YaesuCat(YaesuCat.FT891)

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun commandsAreByteIdentical() {
        assertArrayEquals(ascii(Ft891Cat.readFrequencyCommand()), cat.readFrequencyCommand())
        assertArrayEquals(ascii(Ft891Cat.readModeCommand()), cat.readModeCommand())
        assertArrayEquals(ascii(Ft891Cat.txOnCommand()), cat.pttCommand(true))
        assertArrayEquals(ascii(Ft891Cat.txOffCommand()), cat.pttCommand(false))
        assertArrayEquals(
            ascii(Ft891Cat.setModeCommand(Ft891Cat.Mode.DATA_USB)),
            cat.setDataModeCommand(),
        )
        for (hz in longArrayOf(30_000, 1_840_000, 7_074_000, 14_074_000, 28_074_000, 56_000_000)) {
            assertArrayEquals(ascii(Ft891Cat.setFrequencyCommand(hz)), cat.setFrequencyCommand(hz))
        }
    }

    @Test
    fun frequencyRangeMatches() {
        assertEquals(Ft891Cat.MIN_FREQ_HZ, YaesuCat.FT891.minFreqHz)
        assertEquals(Ft891Cat.MAX_FREQ_HZ, YaesuCat.FT891.maxFreqHz)
    }

    @Test
    fun everyLegacyModeLabelParsesIdentically() {
        for (mode in Ft891Cat.Mode.entries) {
            val reply = ascii("MD0${mode.code};")
            assertEquals(mode.label, cat.parseModeLabel(reply))
        }
    }
}
