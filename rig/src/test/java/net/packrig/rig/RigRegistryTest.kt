package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigRegistryTest {

    @Test
    fun idsAreUnique() {
        val ids = RigRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyDescriptorResolvesAProtocolAndHasNonNegativePort() {
        for (d in RigRegistry.all) {
            // Generics may have null protocolFactory (e.g. generic-rts audio-only).
            // Non-null factories must produce a valid protocol.
            if (d.protocolFactory != null) {
                assertNotNull("${d.id} protocol", d.protocolFactory.invoke())
            }
            assertTrue("${d.id} port index >= 0", d.catPortIndex >= 0)
            assertTrue("${d.id} baud > 0", d.defaultBaud > 0)
        }
    }

    @Test
    fun ft891IsPresentVerifiedAndAutoPtt() {
        val ft891 = RigRegistry.byId("ft891")
        assertNotNull(ft891)
        assertTrue(ft891!!.transportVerified)
        assertEquals(PttMethod.AUTO, ft891.defaultPtt)
        assertEquals(0, ft891.catPortIndex)
    }

    @Test
    fun builtInUsbRigsUseCatPtt_unverifiedOnesFlagged() {
        // Still CAT-from-manual only; flip an id out of this list when its
        // transport is confirmed on real hardware (see docs/RIG_MODELS.md).
        for (id in listOf("ft991a", "ftdx10", "ft710", "ftdx101")) {
            val d = RigRegistry.byId(id)
            assertNotNull("$id present", d)
            assertFalse("$id transport unverified", d!!.transportVerified)
            assertEquals("$id CAT PTT", PttMethod.CAT, d.defaultPtt)
        }
    }

    @Test
    fun ftx1IsBenchVerified_catPttOnEnhancedPort() {
        // Bench 2026-07-09: stock CP2105, CAT confirmed on port 0 (Enhanced).
        val d = RigRegistry.byId("ftx1")
        assertNotNull(d)
        assertTrue(d!!.transportVerified)
        assertEquals(PttMethod.CAT, d.defaultPtt)
        assertEquals(0, d.catPortIndex)
    }

    @Test
    fun byIdReturnsNullForUnknown() {
        assertNull(RigRegistry.byId("nonexistent"))
    }

    @Test
    fun ft891ProtocolIsByteEquivalentToYaesuFt891() {
        val cat = RigRegistry.byId("ft891")!!.protocolFactory!!.invoke()
        assertEquals(
            "FA014074000;",
            cat.setFrequencyCommand(14_074_000)!!.toString(Charsets.US_ASCII),
        )
    }
}
