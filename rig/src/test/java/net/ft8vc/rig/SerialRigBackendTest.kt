package net.ft8vc.rig

import net.ft8vc.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialRigBackendTest {

    private val transport = FakeSerialTransport()
    private val backend = SerialRigBackend(transport, YaesuCat(YaesuCat.FT891))

    @Test
    fun open_deassertsRtsSoPttCannotStartKeyed() {
        assertTrue(backend.open())
        assertTrue(transport.opened)
        assertEquals(listOf(false), transport.rtsEdges)
    }

    @Test
    fun open_failsWhenTransportFails() {
        transport.openResult = false
        assertFalse(backend.open())
        // No RTS activity on a port we never opened.
        assertEquals(emptyList<Boolean>(), transport.rtsEdges)
    }

    @Test
    fun frequencyQuery_writesFaAndParsesReply() {
        transport.enqueueReply("FA014074000;")
        assertEquals(14_074_000L, backend.frequencyHz())
        assertEquals(listOf("FA;"), transport.writtenAscii())
    }

    @Test
    fun frequencyQuery_reassemblesPartialReads() {
        transport.enqueueReply("FA007074000;")
        transport.readChunkLimit = 3
        assertEquals(7_074_000L, backend.frequencyHz())
    }

    @Test
    fun frequencyQuery_timesOutToNull() {
        // No reply enqueued; advance the injected clock 600 ms per look so the
        // 1 s reply deadline expires after a couple of loop iterations.
        var t = 0L
        val slow = SerialRigBackend(transport, YaesuCat(YaesuCat.FT891)) { t += 600; t }
        assertNull(slow.frequencyHz())
    }

    @Test
    fun frequencyQuery_writeFailureIsNull() {
        transport.failWrites = true
        assertNull(backend.frequencyHz())
    }

    @Test
    fun setFrequency_inRangeWrites_outOfRangeDoesNot()  {
        assertTrue(backend.setFrequencyHz(7_074_000L))
        assertEquals(listOf("FA007074000;"), transport.writtenAscii())
        assertFalse(backend.setFrequencyHz(60_000_000L))
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun modeLabel_roundTrip_andDataMode() {
        transport.enqueueReply("MD0C;")
        assertEquals("DATA-U", backend.modeLabel())
        assertTrue(backend.setDataMode())
        assertEquals(listOf("MD0;", "MD0C;"), transport.writtenAscii())
        assertEquals("DATA-U", backend.dataModeLabel())
    }

    @Test
    fun pttKeyAndRelease_driveRtsOnly() {
        backend.keyPtt()
        backend.releasePtt()
        assertEquals(listOf(true, false), transport.rtsEdges)
        assertEquals(0, transport.writes.size)
    }

    @Test
    fun catPtt_writesYaesuTxCommands() {
        assertTrue(backend.catPtt(true))
        assertTrue(backend.catPtt(false))
        assertEquals(listOf("TX1;", "TX0;"), transport.writtenAscii())
    }

    @Test
    fun close_releasesRtsBeforeClosingTransport() {
        backend.open()
        backend.close()
        assertEquals(listOf(false, false), transport.rtsEdges)
        assertFalse(transport.opened)
    }
}
