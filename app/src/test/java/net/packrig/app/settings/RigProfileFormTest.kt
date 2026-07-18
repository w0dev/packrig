package net.packrig.app.settings

import net.packrig.rig.CatProtocols
import net.packrig.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun civAddressField_showsOnCivPresetsAndCivGenericsOnly() {
        assertTrue(RigProfileForm.showsCivAddressField("ic7300", null))
        assertTrue(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_CAT, CatProtocols.ICOM_CIV))
        assertTrue(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_DIGIRIG, CatProtocols.ICOM_CIV))
        assertFalse(RigProfileForm.showsCivAddressField("ft891", null))
        assertFalse(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_CAT, CatProtocols.YAESU_NEWCAT))
        assertFalse(RigProfileForm.showsCivAddressField(RigRegistry.GENERIC_RTS, null))
    }

    @Test
    fun civAddress_parsesTwoHexDigitsInControllerSafeRange() {
        assertEquals(0x94, RigProfileForm.parseCivAddress("94"))
        assertEquals(0xA4, RigProfileForm.parseCivAddress(" a4 "))
        assertNull(RigProfileForm.parseCivAddress("E0")) // reserved for this app
        assertNull(RigProfileForm.parseCivAddress("00"))
        assertNull(RigProfileForm.parseCivAddress("zz"))
        assertNull(RigProfileForm.parseCivAddress("123"))
    }

    @Test
    fun civAddressError_requiredOnGenericsOptionalOnPresets() {
        assertNotNull(RigProfileForm.civAddressError("", RigRegistry.GENERIC_CAT, CatProtocols.ICOM_CIV))
        assertNull(RigProfileForm.civAddressError("", "ic7300", null)) // blank = preset default
        assertNotNull(RigProfileForm.civAddressError("xx", "ic7300", null))
        assertNull(RigProfileForm.civAddressError("94", RigRegistry.GENERIC_CAT, CatProtocols.ICOM_CIV))
        assertNull(RigProfileForm.civAddressError("anything", "ft891", null)) // field hidden → no error
    }

    @Test
    fun protocolLabel_namesFamilyForNamedCatPresets() {
        assertEquals("CI-V (Icom)", RigProfileForm.protocolLabel("ic7300"))
        assertEquals("Serial CAT (Yaesu)", RigProfileForm.protocolLabel("ft891"))
        assertNull(RigProfileForm.protocolLabel(RigRegistry.GENERIC_CAT))   // generics pick their own
        assertNull(RigProfileForm.protocolLabel(RigRegistry.GENERIC_RTS))   // no CAT at all
    }
}
