package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignMatcherTest {

    @Test
    fun baseExtractsCallsignSegment() {
        assertEquals("K1ABC", CallsignMatcher.base("K1ABC"))
        assertEquals("K1ABC", CallsignMatcher.base("K1ABC/P"))
        assertEquals("K1ABC", CallsignMatcher.base("PJ4/K1ABC"))
        assertEquals("K1ABC", CallsignMatcher.base("<PJ4/K1ABC>"))
        assertEquals("K1ABC", CallsignMatcher.base("k1abc/qrp"))
        assertEquals("W0DEV", CallsignMatcher.base("W0DEV-1"))
    }

    @Test
    fun matchesExactIgnoringCaseAndBrackets() {
        assertTrue(CallsignMatcher.matches("k1abc", "K1ABC"))
        assertTrue(CallsignMatcher.matches("<K1ABC>", "K1ABC"))
        assertTrue(CallsignMatcher.matches("<PJ4/K1ABC>", "PJ4/K1ABC"))
    }

    @Test
    fun matchesCompoundFormsViaBase() {
        assertTrue(CallsignMatcher.matches("K1ABC/P", "K1ABC"))
        assertTrue(CallsignMatcher.matches("K1ABC", "K1ABC/QRP"))
        assertTrue(CallsignMatcher.matches("<PJ4/K1ABC>", "K1ABC"))
        assertTrue(CallsignMatcher.matches("PJ4/K1ABC", "K1ABC/P"))
    }

    @Test
    fun rejectsDigitlessBasesAndNonMatches() {
        assertFalse(CallsignMatcher.matches("K2DEF", "K1ABC"))
        // Digitless tokens never base-match (modifiers like DX/POTA/NA).
        assertFalse(CallsignMatcher.matches("POTA/X", "POTA"))
        assertFalse(CallsignMatcher.matches("", "K1ABC"))
        assertFalse(CallsignMatcher.matches("K1ABC", ""))
    }
}
