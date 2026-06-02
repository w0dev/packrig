package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Ft891CatTest {

    @Test
    fun setFrequencyPadsToNineDigits() {
        // 14.074 MHz, the FT8 calling frequency on 20 m.
        assertEquals("FA014074000;", Ft891Cat.setFrequencyCommand(14_074_000))
    }

    @Test
    fun setFrequencyRejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) {
            Ft891Cat.setFrequencyCommand(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ft891Cat.setFrequencyCommand(60_000_000)
        }
    }

    @Test
    fun parseFrequencyRoundTrips() {
        val cmd = Ft891Cat.setFrequencyCommand(7_074_000)
        // The radio echoes the same opcode+payload for a query reply.
        assertEquals(7_074_000L, Ft891Cat.parseFrequencyResponse(cmd))
    }

    @Test
    fun parseFrequencyRejectsGarbage() {
        assertNull(Ft891Cat.parseFrequencyResponse("MD0C;"))
        assertNull(Ft891Cat.parseFrequencyResponse("FA12345;"))
        assertNull(Ft891Cat.parseFrequencyResponse("FAabcdefghi;"))
        assertNull(Ft891Cat.parseFrequencyResponse(""))
    }

    @Test
    fun parseFrequencyToleratesWhitespace() {
        assertEquals(14_074_000L, Ft891Cat.parseFrequencyResponse("  FA014074000;\r\n"))
    }

    @Test
    fun setModeUsesOpcode() {
        assertEquals("MD0C;", Ft891Cat.setModeCommand(Ft891Cat.Mode.DATA_USB))
        assertEquals("MD02;", Ft891Cat.setModeCommand(Ft891Cat.Mode.USB))
    }

    @Test
    fun parseModeReadsVfoDigitAndCode() {
        assertEquals(Ft891Cat.Mode.DATA_USB, Ft891Cat.parseModeResponse("MD0C;"))
        assertEquals(Ft891Cat.Mode.USB, Ft891Cat.parseModeResponse("MD02;"))
    }

    @Test
    fun parseModeRejectsUnknownCode() {
        assertNull(Ft891Cat.parseModeResponse("MD0Z;"))
        assertNull(Ft891Cat.parseModeResponse("FA014074000;"))
    }

    @Test
    fun modeCodesAreUnique() {
        val codes = Ft891Cat.Mode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
