package net.ft8vc.rig.fakes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSerialTransportSelfTest {

    @Test
    fun writesAreRecordedAsAscii() {
        val t = FakeSerialTransport()
        assertTrue(t.write("FA;".toByteArray(Charsets.US_ASCII), 200))
        assertEquals(listOf("FA;"), t.writtenAscii())
    }

    @Test
    fun failWrites_makesWriteReturnFalse() {
        val t = FakeSerialTransport()
        t.failWrites = true
        assertFalse(t.write("FA;".toByteArray(Charsets.US_ASCII), 200))
    }

    @Test
    fun enqueuedReplyIsReadBack_respectingChunkLimit() {
        val t = FakeSerialTransport()
        t.enqueueReply("FA014074000;")
        t.readChunkLimit = 5
        val buf = ByteArray(64)

        assertEquals(5, t.read(buf, 200))
        assertEquals("FA014", String(buf, 0, 5, Charsets.US_ASCII))
        assertEquals(5, t.read(buf, 200))
        assertEquals(2, t.read(buf, 200))
        // Drained: further reads time out.
        assertEquals(0, t.read(buf, 200))
    }

    @Test
    fun rtsEdgesAreRecorded() {
        val t = FakeSerialTransport()
        t.setRts(true)
        t.setRts(false)
        assertEquals(listOf(true, false), t.rtsEdges)
    }

    @Test
    fun openHonoursOpenResult() {
        val t = FakeSerialTransport()
        assertTrue(t.open())
        assertTrue(t.opened)
        t.close()
        assertFalse(t.opened)
        t.openResult = false
        assertFalse(t.open())
    }
}
