package net.packset.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationProfileValidatorTest {

    @Test
    fun isValidCall_acceptsStandardCalls() {
        assertTrue(StationProfileValidator.isValidCall("W0DEV"))
        assertTrue(StationProfileValidator.isValidCall("K1ABC"))
        assertTrue(StationProfileValidator.isValidCall("VE2XYZ"))
        assertTrue(StationProfileValidator.isValidCall("w0dev"))
    }

    @Test
    fun isValidCall_acceptsPortablePrefixSuffix() {
        assertTrue(StationProfileValidator.isValidCall("W0DEV/P"))
        assertTrue(StationProfileValidator.isValidCall("KP4/AB1CD"))
    }

    @Test
    fun isValidCall_rejectsEmptyOrPlaceholders() {
        assertFalse(StationProfileValidator.isValidCall(""))
        assertFalse(StationProfileValidator.isValidCall("  "))
        assertFalse(StationProfileValidator.isValidCall("TEST"))
        assertFalse(StationProfileValidator.isValidCall("CALL"))
    }

    @Test
    fun isValidCall_rejectsAllDigitsOrAllLetters() {
        assertFalse(StationProfileValidator.isValidCall("12345"))
        assertFalse(StationProfileValidator.isValidCall("ABCDE"))
    }

    @Test
    fun isValidCall_rejectsTooShortOrLong() {
        assertFalse(StationProfileValidator.isValidCall("W0"))
        assertFalse(StationProfileValidator.isValidCall("W0DEVABCDEF"))
    }

    @Test
    fun isValidCall_rejectsIllegalChars() {
        assertFalse(StationProfileValidator.isValidCall("W0!DEV"))
        assertFalse(StationProfileValidator.isValidCall("W0 DEV"))
    }

    @Test
    fun isValidGrid_acceptsFourAndSixChar() {
        assertTrue(StationProfileValidator.isValidGrid("EM26"))
        assertTrue(StationProfileValidator.isValidGrid("EM26ab"))
        assertTrue(StationProfileValidator.isValidGrid("FN31"))
    }

    @Test
    fun isValidGrid_rejectsEmptyOrShort() {
        assertFalse(StationProfileValidator.isValidGrid(""))
        assertFalse(StationProfileValidator.isValidGrid("EM2"))
        assertFalse(StationProfileValidator.isValidGrid("EM26X"))
    }

    @Test
    fun isComplete_requiresBoth() {
        assertTrue(StationProfileValidator.isComplete("W0DEV", "EM26"))
        assertFalse(StationProfileValidator.isComplete("W0DEV", ""))
        assertFalse(StationProfileValidator.isComplete("", "EM26"))
        assertFalse(StationProfileValidator.isComplete("TEST", "FN31"))
    }
}
