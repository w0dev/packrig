package net.packrig.app.settings

import net.packrig.rig.PttMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigProfileMigrationTest {

    @Test
    fun ft891MigrationCarriesLegacyKnobs() {
        val p = SettingsRepository.buildMigratedProfile(
            modelId = "ft891", baud = 4_800, catPortOverride = 1, pttPreference = PttPreference.RTS,
        )
        assertEquals("Yaesu FT-891", p.name)
        assertEquals("ft891", p.presetId)
        assertEquals(4_800, p.baud)
        assertEquals(1, p.catPortIndex)
        assertEquals(PttMethod.RTS, p.pttMethod)
        assertNull(p.catProtocolId)
        assertTrue(p.id.isNotBlank())
    }

    @Test
    fun freshDefaultsMigrateToAllNullKnobs() {
        // Parity path: legacy install that never touched baud/PTT → profile with
        // null knobs → RigProfiles.resolve() == the registry entry (RigProfilesTest).
        val p = SettingsRepository.buildMigratedProfile("ft891", null, null, null)
        assertNull(p.baud)
        assertNull(p.catPortIndex)
        assertNull(p.pttMethod)
    }

    @Test
    fun unknownLegacyModelKeepsIdAsNameAndPreset() {
        val p = SettingsRepository.buildMigratedProfile("mystery9000", null, null, null)
        assertEquals("mystery9000", p.name)
        assertEquals("mystery9000", p.presetId)
    }
}
