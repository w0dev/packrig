package net.packset.rig

import net.packset.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backend with no CAT protocol (generic-rts preset) must still key RTS PTT
 *  and answer every CAT call with a fast null/false — no reads, no timeouts. */
class SerialRigBackendNoCatTest {

    private fun noCatBackend(transport: FakeSerialTransport) =
        SerialRigBackend(transport, null)

    @Test
    fun rtsPttStillWorksWithoutProtocol() {
        val transport = FakeSerialTransport()
        val backend = noCatBackend(transport)
        assertTrue(backend.open())
        backend.keyPtt()
        backend.releasePtt()
        assertEquals(listOf(false, true, false), transport.rtsEdges)
    }

    @Test
    fun catCallsAreNullSafeAndWriteNothing() {
        val transport = FakeSerialTransport()
        val backend = noCatBackend(transport)
        assertTrue(backend.open())
        assertNull(backend.frequencyHz())
        assertNull(backend.modeLabel())
        assertFalse(backend.setFrequencyHz(14_074_000L))
        assertFalse(backend.setDataMode())
        assertFalse(backend.catPtt(true))
        assertEquals("No CAT", backend.dataModeLabel())
        assertEquals(0, transport.writes.size)
    }
}
