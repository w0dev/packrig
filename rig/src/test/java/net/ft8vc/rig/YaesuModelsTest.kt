package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Family specs are validated by invariants that hold regardless of a model's
 * exact band edges: the FT8 calling frequencies on every HF/6 m band must be
 * accepted, clearly-out values rejected, and the DATA-U mode must round-trip.
 * Exact min/max come from each rig's CAT manual (see YaesuModels.kt comments);
 * do NOT assert fabricated boundary numbers here.
 */
class YaesuModelsTest {

    private val ft8CallingFreqs = longArrayOf(
        1_840_000, 3_573_000, 7_074_000, 10_136_000, 14_074_000,
        18_100_000, 21_074_000, 24_915_000, 28_074_000, 50_313_000,
    )

    private val allSpecs = listOf(
        YaesuModels.FT991A, YaesuModels.FTDX10, YaesuModels.FT710,
        YaesuModels.FTDX101, YaesuModels.FTX1,
    )

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun everyModelAcceptsAllHfAndSixMeterFt8Frequencies() {
        for (spec in allSpecs) {
            val cat = YaesuCat(spec)
            for (hz in ft8CallingFreqs) {
                assertNotNull(
                    "${spec.name} should accept $hz Hz",
                    cat.setFrequencyCommand(hz),
                )
            }
        }
    }

    @Test
    fun everyModelRejectsSubMinimumAndParsesGarbageAsNull() {
        for (spec in allSpecs) {
            val cat = YaesuCat(spec)
            assertNull("${spec.name} should reject 0 Hz", cat.setFrequencyCommand(0))
            assertNull("${spec.name} garbage parse", cat.parseFrequency(ascii("MD0C;")))
        }
    }

    @Test
    fun everyModelUsesDataUsbForFt8() {
        for (spec in allSpecs) {
            val cat = YaesuCat(spec)
            assertEquals("${spec.name} data mode", "DATA-U", cat.dataModeLabel)
            assertEquals("${spec.name} MD0C parse", "DATA-U", cat.parseModeLabel(ascii("MD0C;")))
        }
    }

    @Test
    fun specsAreDistinctByName() {
        val names = allSpecs.map { it.name } + YaesuCat.FT891.name
        assertEquals(names.size, names.toSet().size)
    }

    // Bench evidence 2026-07-09: owner FTX-1 read at 444.0925 MHz over CAT
    // (FA444092570;) — the rig covers VHF/UHF, so its spec must accept them.
    @Test
    fun ftx1AcceptsVhfAndUhf() {
        val cat = YaesuCat(YaesuModels.FTX1)
        assertNotNull(cat.setFrequencyCommand(144_174_000)) // 2 m FT8
        assertNotNull(cat.setFrequencyCommand(444_092_570)) // observed on bench
        assertEquals(444_092_570L, cat.parseFrequency("FA444092570;".toByteArray(Charsets.US_ASCII)))
    }
}
