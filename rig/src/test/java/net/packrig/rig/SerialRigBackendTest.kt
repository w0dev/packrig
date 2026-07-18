package net.packrig.rig

import net.packrig.rig.fakes.FakeSerialTransport
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
    fun frequencyQuery_readErrorIsNull() {
        // No reply enqueued; the transport reports a read error (e.g. USB
        // detach mid-exchange). The real clock is fine here since the error
        // path returns immediately — we're not relying on the deadline.
        transport.failReads = true
        assertNull(backend.frequencyHz())
        assertEquals(listOf("FA;"), transport.writtenAscii())
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

    /** Minimal acked/flushing protocol: frames are `<class-byte>!`, ack = 'K', nak = 'N'. */
    private open class AckedStubProtocol : CatProtocol {
        override val dataModeLabel = "STUB"
        override val wantsInputFlush = true
        override val setCommandsAcked = true
        override fun readFrequencyCommand() = byteArrayOf('Q'.code.toByte())
        override fun setFrequencyCommand(hz: Long): ByteArray = byteArrayOf('S'.code.toByte())
        override fun parseFrequency(reply: ByteArray): Long? =
            if (reply.firstOrNull() == 'R'.code.toByte()) 7_074_000L else null
        override fun readModeCommand() = byteArrayOf('M'.code.toByte())
        override fun parseModeLabel(reply: ByteArray): String? = null
        override fun setDataModeCommand() = byteArrayOf('D'.code.toByte())
        override fun pttCommand(on: Boolean) = byteArrayOf('P'.code.toByte())
        override fun splitFrames(bytes: ByteArray): FrameSplit {
            val frames = mutableListOf<ByteArray>()
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == '!'.code.toByte()) {
                    frames += bytes.copyOfRange(start, i + 1)
                    start = i + 1
                }
            }
            return FrameSplit(frames, bytes.copyOfRange(start, bytes.size))
        }
        override fun classifyFrame(frame: ByteArray): FrameClass = when (frame.firstOrNull()) {
            'R'.code.toByte() -> FrameClass.Reply
            'E'.code.toByte() -> FrameClass.Echo
            'K'.code.toByte() -> FrameClass.Ack
            'N'.code.toByte() -> FrameClass.Nak
            'B'.code.toByte() -> FrameClass.Broadcast
            else -> FrameClass.Junk
        }
    }

    @Test
    fun exchange_skipsEchoAndJunkFramesBeforeReply() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, AckedStubProtocol())
        t.enqueueOnWrite("E!x!R!")
        assertEquals(7_074_000L, b.frequencyHz())
    }

    @Test
    fun ackedSetCommand_trueOnAck_falseOnNak() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, AckedStubProtocol())
        t.enqueueOnWrite("K!")
        assertTrue(b.setDataMode())
        t.enqueueOnWrite("N!")
        assertFalse(b.setDataMode())
    }

    @Test
    fun ackedSetCommand_falseOnTimeout() {
        val t = FakeSerialTransport()
        var clock = 0L
        val b = SerialRigBackend(t, AckedStubProtocol()) { clock += 600; clock }
        assertFalse(b.setDataMode())
    }

    @Test
    fun flush_drainsStaleBytesBeforeExchange() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, AckedStubProtocol())
        t.enqueueReply("K!")          // stale ack from a previous set
        t.enqueueOnWrite("R!")        // the actual reply, delivered post-write
        assertEquals(7_074_000L, b.frequencyHz())
    }

    @Test
    fun frequency_fallsBackToBroadcastWhenReplyNeverComes() {
        val t = FakeSerialTransport()
        var clock = 0L
        val b = SerialRigBackend(t, object : AckedStubProtocol() {
            override fun parseFrequency(reply: ByteArray): Long? = when (reply.firstOrNull()) {
                'B'.code.toByte() -> 14_074_000L
                'R'.code.toByte() -> 7_074_000L
                else -> null
            }
        }, { clock += 300; clock })
        t.enqueueOnWrite("B!")
        assertEquals(14_074_000L, b.frequencyHz())
    }

    @Test
    fun probe_echoOnlyWhenOnlyOurEchoComesBack() {
        val t = FakeSerialTransport()
        var clock = 0L
        val b = SerialRigBackend(t, AckedStubProtocol()) { clock += 300; clock }
        t.enqueueOnWrite("E!")
        assertEquals(ProbeResult.EchoOnly, b.probeFrequency())
    }

    @Test
    fun probe_broadcastFrequencyCountsAsSync() {
        val t = FakeSerialTransport()
        val b = SerialRigBackend(t, object : AckedStubProtocol() {
            override fun parseFrequency(reply: ByteArray): Long? =
                if (reply.firstOrNull() == 'B'.code.toByte()) 14_074_000L else null
        })
        t.enqueueOnWrite("B!")
        assertEquals(ProbeResult.Sync(14_074_000L), b.probeFrequency())
    }
}
