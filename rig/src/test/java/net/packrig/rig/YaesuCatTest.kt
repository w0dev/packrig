package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YaesuCatTest {

    private val cat = YaesuCat(YaesuCat.FT891)

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun readFrequencyCommandIsFaQuery() {
        assertEquals("FA;", cat.readFrequencyCommand().toString(Charsets.US_ASCII))
    }

    @Test
    fun setFrequencyPadsToNineDigits() {
        // 14.074 MHz, the FT8 calling frequency on 20 m.
        assertEquals(
            "FA014074000;",
            cat.setFrequencyCommand(14_074_000)!!.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun setFrequencyRejectsOutOfRange() {
        assertNull(cat.setFrequencyCommand(0))
        assertNull(cat.setFrequencyCommand(60_000_000))
    }

    @Test
    fun parseFrequencyRoundTrips() {
        val cmd = cat.setFrequencyCommand(7_074_000)!!
        // The radio echoes the same opcode+payload for a query reply.
        assertEquals(7_074_000L, cat.parseFrequency(cmd))
    }

    @Test
    fun parseFrequencyRejectsGarbage() {
        assertNull(cat.parseFrequency(ascii("MD0C;")))
        assertNull(cat.parseFrequency(ascii("FA12345;")))
        assertNull(cat.parseFrequency(ascii("FAabcdefghi;")))
        assertNull(cat.parseFrequency(ascii("")))
    }

    @Test
    fun parseFrequencyToleratesWhitespace() {
        assertEquals(14_074_000L, cat.parseFrequency(ascii("  FA014074000;\r\n")))
    }

    @Test
    fun setDataModeSelectsDataUsb() {
        assertEquals("MD0C;", cat.setDataModeCommand().toString(Charsets.US_ASCII))
        assertEquals("DATA-U", cat.dataModeLabel)
    }

    @Test
    fun parseModeReadsVfoDigitAndCode() {
        assertEquals("DATA-U", cat.parseModeLabel(ascii("MD0C;")))
        assertEquals("USB", cat.parseModeLabel(ascii("MD02;")))
        // Bare code without the VFO digit is tolerated (matches Ft891Cat).
        assertEquals("USB", cat.parseModeLabel(ascii("MD2;")))
    }

    @Test
    fun parseModeRejectsUnknownCode() {
        assertNull(cat.parseModeLabel(ascii("MD0Z;")))
        assertNull(cat.parseModeLabel(ascii("FA014074000;")))
    }

    @Test
    fun pttCommandsMatchYaesuTxOpcodes() {
        // YaesuCat narrows pttCommand's return to non-null ByteArray.
        assertEquals("TX1;", cat.pttCommand(true).toString(Charsets.US_ASCII))
        assertEquals("TX0;", cat.pttCommand(false).toString(Charsets.US_ASCII))
    }

    @Test
    fun replyTerminatorIsSemicolon() {
        assertEquals(';'.code.toByte(), cat.replyTerminator)
    }

    @Test
    fun splitFrames_splitsCompleteFramesAndKeepsRemainder() {
        val split = cat.splitFrames("FA014074000;MD02;FA0".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf("FA014074000;", "MD02;"), split.frames.map { it.toString(Charsets.US_ASCII) })
        assertEquals("FA0", split.remainder.toString(Charsets.US_ASCII))
    }

    @Test
    fun splitFrames_noTerminatorIsAllRemainder() {
        val split = cat.splitFrames("FA0140".toByteArray(Charsets.US_ASCII))
        assertTrue(split.frames.isEmpty())
        assertEquals("FA0140", split.remainder.toString(Charsets.US_ASCII))
    }

    @Test
    fun framingDefaults_matchLegacyYaesuBehavior() {
        assertEquals(FrameClass.Reply, cat.classifyFrame("FA014074000;".toByteArray(Charsets.US_ASCII)))
        assertFalse(cat.wantsInputFlush)
        assertFalse(cat.setCommandsAcked)
    }
}
