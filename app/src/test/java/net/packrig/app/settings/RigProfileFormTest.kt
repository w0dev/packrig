package net.packrig.app.settings

import net.packrig.rig.RigRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RigProfileFormTest {

    @Test
    fun catPortShownForAnyCatPresetOnMultiPortBridge() {
        // Named model presets expose the override too (owner decision 2026-07-11,
        // FTX-1 field session) — defaults still ship from the preset.
        assertTrue(RigProfileForm.showsCatPortPicker("ftx1", portCount = 2))
        assertTrue(RigProfileForm.showsCatPortPicker("ft891", portCount = 2))
        assertTrue(RigProfileForm.showsCatPortPicker(RigRegistry.GENERIC_CAT, portCount = 2))
        assertTrue(RigProfileForm.showsCatPortPicker(RigRegistry.GENERIC_DIGIRIG, portCount = 2))
    }

    @Test
    fun catPortHiddenOnSinglePortBridge() {
        assertFalse(RigProfileForm.showsCatPortPicker("ft891", portCount = 1))
        assertFalse(RigProfileForm.showsCatPortPicker(RigRegistry.GENERIC_CAT, portCount = 1))
        assertFalse(RigProfileForm.showsCatPortPicker("ftx1", portCount = 0))
    }

    @Test
    fun catPortHiddenForCatlessOrUnknownPresets() {
        assertFalse(RigProfileForm.showsCatPortPicker("generic-rts", portCount = 2))
        assertFalse(RigProfileForm.showsCatPortPicker("nonsense", portCount = 2))
        assertFalse(RigProfileForm.showsCatPortPicker("", portCount = 2))
    }
}
