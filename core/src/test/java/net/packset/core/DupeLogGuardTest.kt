package net.packset.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DupeLogGuardTest {

    @Test
    fun suppressesRepeatWithinWindowAllowsAfter() {
        val g = DupeLogGuard(windowMs = 600_000L)
        assertTrue(g.shouldLog("K1ABC", 0L))
        assertFalse(g.shouldLog("K1ABC", 599_999L))
        assertTrue(g.shouldLog("K1ABC", 600_000L + 599_999L)) // window measured from last LOG
    }

    @Test
    fun differentCallsAreIndependent()  {
        val g = DupeLogGuard(windowMs = 600_000L)
        assertTrue(g.shouldLog("K1ABC", 0L))
        assertTrue(g.shouldLog("K2DEF", 1L))
    }

    @Test
    fun compoundFormsShareOneWindow() {
        val g = DupeLogGuard(windowMs = 600_000L)
        assertTrue(g.shouldLog("K1ABC", 0L))
        assertFalse(g.shouldLog("K1ABC/P", 1_000L))
        assertFalse(g.shouldLog("<PJ4/K1ABC>", 2_000L))
    }
}
