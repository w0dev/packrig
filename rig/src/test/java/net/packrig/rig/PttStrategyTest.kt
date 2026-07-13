package net.packrig.rig

import net.packrig.rig.fakes.FakeRigBackend
import net.packrig.rig.fakes.FakeSerialTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PttStrategyTest {

    @Test
    fun rtsStrategy_drivesTheRtsLine() {
        val transport = FakeSerialTransport()
        val ptt = RtsPttStrategy(transport)
        assertTrue(ptt.key())
        assertTrue(ptt.release())
        assertEquals(listOf(true, false), transport.rtsEdges)
    }

    @Test
    fun catStrategy_delegatesToCatPtt() {
        val cat = FakeRigBackend()
        val ptt = CatPttStrategy(cat)
        assertTrue(ptt.key())
        assertTrue(ptt.release())
        assertEquals(2, cat.catPttInvocations)
    }
}
