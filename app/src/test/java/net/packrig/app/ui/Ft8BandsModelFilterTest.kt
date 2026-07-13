package net.packrig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dial presets are filtered through the selected model's CAT protocol, so the
 * dropdown only offers bands the rig can actually tune (FTX-1 bench 2026-07-09:
 * the rig covers VHF/UHF; an FT-891 does not).
 */
class Ft8BandsModelFilterTest {

    private fun labels(modelId: String?) = presetsForModel(modelId).map { it.label }.toSet()

    @Test
    fun ft891GetsHfAndSixMeters_butNoVhfUhf() {
        val bands = labels("ft891")
        assertTrue("6m present", "6m" in bands)
        assertTrue("20m present", "20m" in bands)
        assertFalse("2m excluded", "2m" in bands)
        assertFalse("70cm excluded", "70cm" in bands)
    }

    @Test
    fun ftx1GetsVhfAndUhf() {
        val bands = labels("ftx1")
        assertTrue("2m present", "2m" in bands)
        assertTrue("70cm present", "70cm" in bands)
        assertTrue("6m present", "6m" in bands)
    }

    @Test
    fun noModelOrUnknownModelShowsEveryPreset() {
        assertEquals(Ft8DialPresets, presetsForModel(null))
        assertEquals(Ft8DialPresets, presetsForModel("no-such-rig"))
    }

    @Test
    fun presetTableContainsTheNewBands() {
        val all = Ft8DialPresets.map { it.label }.toSet()
        assertTrue(setOf("6m", "2m", "70cm").all { it in all })
        assertEquals(50_313_000L, Ft8DialPresets.first { it.label == "6m" }.hz)
        assertEquals(144_174_000L, Ft8DialPresets.first { it.label == "2m" }.hz)
        assertEquals(432_174_000L, Ft8DialPresets.first { it.label == "70cm" }.hz)
    }
}
