package net.packset.core

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

    @Test
    fun parsesAndFormatsParkRefLists() {
        assertEquals(listOf("US-3315", "US-0891"), ActivationProfile.parseParkRefs(" us-3315 , us-0891 "))
        assertEquals(emptyList<String>(), ActivationProfile.parseParkRefs(null))
        assertEquals(emptyList<String>(), ActivationProfile.parseParkRefs("banana"))
        assertEquals("US-3315,US-0891", ActivationProfile.formatParkRefs(listOf("us-3315", "US-0891", "us-3315")))
        assertNull(ActivationProfile.formatParkRefs(emptyList()))
    }

    @Test
    fun validatesParkRefLists() {
        assertTrue(ActivationProfile.isValidParkRefList("US-3315"))
        assertTrue(ActivationProfile.isValidParkRefList("us-3315, us-0891"))
        assertFalse(ActivationProfile.isValidParkRefList("US-3315, banana"))
        assertFalse(ActivationProfile.isValidParkRefList(""))
        assertFalse(ActivationProfile.isValidParkRefList(" , "))
    }

    @Test
    fun parkRefsForLoggingRespectsPotaMode() {
        assertNull(ActivationProfile.parkRefsForLogging(false, "US-3315"))
        assertEquals("US-3315", ActivationProfile.parkRefsForLogging(true, "us-3315"))
        assertEquals("US-3315,US-0891", ActivationProfile.parkRefsForLogging(true, "US-3315, US-0891"))
        assertNull(ActivationProfile.parkRefsForLogging(true, ""))
    }
}
