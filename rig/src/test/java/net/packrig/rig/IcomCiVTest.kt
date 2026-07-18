package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcomCiVTest {

    private val spec = IcomModelSpec(
        name = "Test rig",
        civAddress = 0x94,
        minFreqHz = 30_000L,
        maxFreqHz = 74_800_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )
    private val civ = IcomCiV(spec)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun readFrequencyCommand_isCmd03() {
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD).toList(),
            civ.readFrequencyCommand().toList(),
        )
    }

    @Test
    fun setFrequencyCommand_encodes14074AsLittleEndianBcd() {
        // FT8CN golden frame for 14.074 MHz: FE FE <addr> E0 05 00 40 07 14 00 FD
        assertEquals(
            bytes(0xFE, 0xFE, 0x94, 0xE0, 0x05, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD).toList(),
            civ.setFrequencyCommand(14_074_000L)!!.toList(),
        )
    }

    @Test
    fun setFrequencyCommand_outOfModelRangeIsNull() {
        assertNull(civ.setFrequencyCommand(144_174_000L))
    }

    @Test
    fun parseFrequency_readsPollReply() {
        val reply = bytes(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        assertEquals(14_074_000L, civ.parseFrequency(reply))
    }

    @Test
    fun parseFrequency_readsTransceiveBroadcast() {
        val transceive = bytes(0xFE, 0xFE, 0x00, 0x94, 0x00, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        assertEquals(14_074_000L, civ.parseFrequency(transceive))
    }

    @Test
    fun parseFrequency_rejectsNonBcdAndWrongCommand() {
        assertNull(civ.parseFrequency(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0x00, 0x4A, 0x07, 0x14, 0x00, 0xFD)))
        assertNull(civ.parseFrequency(bytes(0xFE, 0xFE, 0xE0, 0x94, 0x04, 0x01, 0x01, 0xFD)))
        assertNull(civ.parseFrequency(bytes(0x03, 0xFD)))
    }

    @Test
    fun splitFrames_handlesNoiseAndPartials() {
        val stream = bytes(0x07, 0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD, 0xFE, 0xFE)
        val split = civ.splitFrames(stream)
        assertEquals(1, split.frames.size)
        assertEquals(
            bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD).toList(),
            split.frames[0].toList(),
        )
        assertEquals(bytes(0xFE, 0xFE).toList(), split.remainder.toList())
    }

    @Test
    fun classifyFrame_coversEchoAckNakBroadcastReplyJunk() {
        val echo = bytes(0xFE, 0xFE, 0x94, 0xE0, 0x03, 0xFD)
        val ack = bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD)
        val nak = bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFA, 0xFD)
        val broadcast = bytes(0xFE, 0xFE, 0x00, 0x94, 0x00, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        val reply = bytes(0xFE, 0xFE, 0xE0, 0x94, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        val otherStation = bytes(0xFE, 0xFE, 0xE0, 0xA4, 0x03, 0x00, 0x40, 0x07, 0x14, 0x00, 0xFD)
        val truncated = bytes(0xFE, 0xFE, 0xE0, 0xFD)
        assertEquals(FrameClass.Echo, civ.classifyFrame(echo))
        assertEquals(FrameClass.Ack, civ.classifyFrame(ack))
        assertEquals(FrameClass.Nak, civ.classifyFrame(nak))
        assertEquals(FrameClass.Broadcast, civ.classifyFrame(broadcast))
        assertEquals(FrameClass.Reply, civ.classifyFrame(reply))
        assertEquals(FrameClass.Junk, civ.classifyFrame(otherStation))
        assertEquals(FrameClass.Junk, civ.classifyFrame(truncated))
    }

    @Test
    fun addressOverride_winsOverModelDefault() {
        val moved = IcomCiV(spec, civAddressOverride = 0x76)
        assertEquals(
            bytes(0xFE, 0xFE, 0x76, 0xE0, 0x03, 0xFD).toList(),
            moved.readFrequencyCommand().toList(),
        )
        assertEquals(FrameClass.Junk, moved.classifyFrame(bytes(0xFE, 0xFE, 0xE0, 0x94, 0xFB, 0xFD)))
    }
}
