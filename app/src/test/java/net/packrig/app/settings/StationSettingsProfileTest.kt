package net.packrig.app.settings

import net.packrig.rig.PttMethod
import net.packrig.rig.RigProfile
import net.packrig.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationSettingsProfileTest {

    private val profile = RigProfile(
        id = "u1", name = "Home 891", presetId = "ft891",
        baud = 4_800, catPortIndex = 1, pttMethod = PttMethod.CAT,
    )

    @Test
    fun noSelectionLeavesLegacyFieldsUntouched() {
        val base = StationSettings(radioModelId = "ftdx10", catBaud = 19_200)
        assertEquals(base, base.withRigProfileApplied())
    }

    @Test
    fun selectedProfileDrivesTheDerivedRigFields() {
        val s = StationSettings(
            rigProfiles = listOf(profile),
            selectedRigProfileId = "u1",
        ).withRigProfileApplied()
        assertEquals("ft891", s.radioModelId)
        assertEquals(4_800, s.catBaud)
        assertEquals(1, s.catPortOverride)
        assertEquals(PttPreference.CAT, s.pttPreference)
    }

    @Test
    fun nullKnobsFallToPresetDefaults() {
        val sparse = RigProfile(id = "u2", name = "Sparse", presetId = "ft891")
        val s = StationSettings(
            rigProfiles = listOf(sparse),
            selectedRigProfileId = "u2",
        ).withRigProfileApplied()
        assertEquals(RigRegistry.byId("ft891")!!.defaultBaud, s.catBaud)
        assertNull(s.catPortOverride)
        assertEquals(PttPreference.AUTO, s.pttPreference) // FT-891 defaultPtt = AUTO
    }

    @Test
    fun unknownPresetStillExposesPresetIdSoUiCanFlagIt() {
        val orphan = RigProfile(id = "u3", name = "Future rig", presetId = "kenwood-ts590")
        val s = StationSettings(
            rigProfiles = listOf(orphan),
            selectedRigProfileId = "u3",
        ).withRigProfileApplied()
        assertEquals("kenwood-ts590", s.radioModelId) // RigRegistry.byId → null → NoModel downstream
    }

    @Test
    fun pttMappingsAreInverse() {
        PttMethod.entries.forEach { assertEquals(it, it.toPreference().toPttMethod()) }
    }
}
