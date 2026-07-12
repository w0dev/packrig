package net.packset.rig

import net.packset.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {

    private fun backend(transport: FakeSerialTransport) =
        SerialRigBackend(transport = transport, protocol = YaesuCat(YaesuCat.FT891))

    @Test
    fun validFrequencyReplyIsSync() {
        val transport = FakeSerialTransport()
        transport.enqueueReply("FA014074000;")
        val backend = backend(transport)
        assertTrue(backend.open())
        assertEquals(ProbeResult.Sync(14_074_000L), backend.probeFrequency())
    }

    @Test
    fun unparseableBytesAreGarbage() {
        val transport = FakeSerialTransport()
        // Raw, non-textual bytes terminated by ';' — all four values are valid
        // US-ASCII code points (0x00, 0x7F, 0x15, ';'), so this round-trips
        // losslessly through FakeSerialTransport's ASCII-string reply API.
        val garbage = byteArrayOf(0x00, 0x7F, 0x15, ';'.code.toByte())
        transport.enqueueReply(garbage.toString(Charsets.US_ASCII))
        val backend = backend(transport)
        assertTrue(backend.open())
        assertEquals(ProbeResult.Garbage, backend.probeFrequency())
    }

    @Test
    fun noBytesIsSilence() {
        val transport = FakeSerialTransport()
        // Inject the clock so the 1 s reply deadline expires without a real sleep.
        var t = 0L
        val backend = SerialRigBackend(transport, YaesuCat(YaesuCat.FT891)) { t += 600; t }
        assertTrue(backend.open())
        assertEquals(ProbeResult.Silence, backend.probeFrequency())
    }

    @Test
    fun nullProtocolIsNoCat() {
        val backend = SerialRigBackend(transport = FakeSerialTransport(), protocol = null)
        assertTrue(backend.open())
        assertEquals(ProbeResult.NoCat, backend.probeFrequency())
    }
}
