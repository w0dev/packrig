package net.ft8vc.rig

import org.junit.Assert.assertEquals
import org.junit.Test

class Cp210xTest {

    @Test
    fun rtsHighSetsStateAndMask() {
        // RTS mask (0x0200) | RTS state (0x0002) = 0x0202.
        assertEquals(0x0202, Cp210x.mhsValue(rts = true))
    }

    @Test
    fun rtsLowSetsMaskOnly() {
        // RTS mask (0x0200) with state cleared = 0x0200.
        assertEquals(0x0200, Cp210x.mhsValue(rts = false))
    }

    @Test
    fun leavesDtrBitsUntouched() {
        // DTR state/mask bits (0x0001 / 0x0100) must never be set.
        listOf(true, false).forEach { rts ->
            val v = Cp210x.mhsValue(rts)
            assertEquals(0, v and 0x0001)
            assertEquals(0, v and 0x0100)
        }
    }

    @Test
    fun lineCtl8n1MatchesConstant() {
        // 8 data bits in the high byte, no parity, 1 stop bit.
        assertEquals(0x0800, Cp210x.lineCtlValue(dataBits = 8, parity = 0, stopBits = 0))
        assertEquals(0x0800, Cp210x.LINE_CTL_8N1)
    }

    @Test
    fun lineCtlPacksParityAndStopBits() {
        // 7 data bits, even parity (2), 2 stop bits (2) -> 0x0722.
        assertEquals(0x0722, Cp210x.lineCtlValue(dataBits = 7, parity = 2, stopBits = 2))
    }

    @Test
    fun baudRateIsLittleEndian() {
        // 38400 = 0x9600 -> bytes 00 96 00 00.
        val bytes = Cp210x.baudRateBytes(38_400)
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x96.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
    }
}
