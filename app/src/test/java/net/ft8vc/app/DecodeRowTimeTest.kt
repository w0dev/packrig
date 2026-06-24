package net.ft8vc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DecodeRowTimeTest {

    @Test
    fun formatsEpochAsAllZeros() {
        assertEquals("000000", formatRowTimeUtc(0L))
    }

    @Test
    fun formatsKnownTimeAsHHmmssInUtc() {
        // 1970-01-01T04:15:30Z = (4*3600 + 15*60 + 30) seconds = 15330 s.
        assertEquals("041530", formatRowTimeUtc(15_330_000L))
    }

    @Test
    fun producesSixDigitsWithNoColons() {
        val s = formatRowTimeUtc(1_700_000_000_000L)
        assertEquals(6, s.length)
        assertFalse("must match RX HHmmss format (no colons)", s.contains(":"))
    }
}
