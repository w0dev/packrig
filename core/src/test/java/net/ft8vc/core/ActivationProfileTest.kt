package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationProfileTest {

    @Test
    fun cqModifierOnlyWhenPotaEnabled() {
        assertNull(ActivationProfile.cqModifier(false))
        assertEquals("POTA", ActivationProfile.cqModifier(true))
    }

    @Test
    fun validatesParkReference() {
        assertTrue(ActivationProfile.isValidParkRef("US-3315"))
        assertTrue(ActivationProfile.isValidParkRef("us-3315"))
        assertEquals("US-3315", ActivationProfile.normalizeParkRef("us-3315"))
        assertFalse(ActivationProfile.isValidParkRef("3315"))
        assertFalse(ActivationProfile.isValidParkRef(""))
    }
}
