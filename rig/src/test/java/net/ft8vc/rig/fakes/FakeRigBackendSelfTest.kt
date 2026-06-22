package net.ft8vc.rig.fakes

import net.ft8vc.rig.Ft891Cat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-test of [FakeRigBackend]: proves each failure-injection switch behaves as
 * the plan declared. These tests do NOT use MockK — the fake is plain Kotlin and
 * is asserted directly.
 */
class FakeRigBackendSelfTest {

    @Test
    fun keyPtt_and_releasePtt_toggleFlagAndAreIdempotent() {
        val fake = FakeRigBackend()
        assertFalse(fake.pttKeyed)

        fake.keyPtt()
        assertTrue(fake.pttKeyed)

        fake.releasePtt()
        assertFalse(fake.pttKeyed)

        // releasePtt is idempotent — calling again must not throw, must stay false.
        fake.releasePtt()
        assertFalse(fake.pttKeyed)
    }

    @Test
    fun frequencyHz_defaultAndSetFrequency_roundTrip() {
        val fake = FakeRigBackend(initialFrequencyHz = 7_074_000L)
        assertEquals(7_074_000L, fake.frequencyHz())

        assertTrue(fake.setFrequencyHz(14_074_000L))
        assertEquals(14_074_000L, fake.frequencyHz())

        // Out-of-range setFrequencyHz returns false and does not mutate state.
        assertFalse(fake.setFrequencyHz(0L))
        assertFalse(fake.setFrequencyHz(60_000_000L))
        assertEquals(14_074_000L, fake.frequencyHz())
    }

    @Test
    fun mode_defaultAndSetMode_roundTrip() {
        val fake = FakeRigBackend()
        assertEquals(Ft891Cat.Mode.DATA_USB, fake.mode())

        assertTrue(fake.setMode(Ft891Cat.Mode.USB))
        assertEquals(Ft891Cat.Mode.USB, fake.mode())
    }

    @Test
    fun configureLatencyMs_delaysFrequencyRead() {
        val fake = FakeRigBackend()
        fake.configureLatencyMs(50L)

        val startNs = System.nanoTime()
        val result = fake.frequencyHz()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertNotNull(result)
        // Allow ~5 ms slop for OS scheduling on slower CI hosts.
        assertTrue("expected >=45 ms delay, got $elapsedMs ms", elapsedMs >= 45L)
    }

    @Test
    fun configureTimeoutHz_andTimeoutMode_returnNullOnce() {
        val fake = FakeRigBackend()

        fake.configureTimeoutHz(true)
        assertNull(fake.frequencyHz())
        // Next read recovers (one-shot semantics).
        assertNotNull(fake.frequencyHz())

        fake.configureTimeoutMode(true)
        assertNull(fake.mode())
        assertNotNull(fake.mode())
    }

    @Test
    fun simulateDetach_disablesEverything_reattachRecovers() {
        val fake = FakeRigBackend()

        fake.simulateDetach()
        fake.keyPtt()
        fake.releasePtt()
        assertNull(fake.frequencyHz())
        assertNull(fake.mode())
        assertFalse(fake.setFrequencyHz(7_074_000L))
        assertFalse(fake.setMode(Ft891Cat.Mode.USB))
        assertFalse(fake.catPtt(true))

        val detachedEdges = fake.pttEdgesSnapshot().count { it.kind == PttEdgeKind.DETACHED }
        assertEquals(2, detachedEdges)

        fake.reattach()
        assertNotNull(fake.frequencyHz())
        assertNotNull(fake.mode())
        fake.keyPtt()
        assertTrue(fake.pttKeyed)
    }

    @Test
    fun pttEdgesSnapshot_recordsEveryKeyAndRelease_inOrder() {
        var fakeClock = 1_000L
        val fake = FakeRigBackend(clock = { fakeClock })

        fake.keyPtt(); fakeClock = 1_100L
        fake.releasePtt(); fakeClock = 1_200L
        fake.keyPtt(); fakeClock = 1_300L
        fake.releasePtt()

        val edges = fake.pttEdgesSnapshot()
        assertEquals(4, edges.size)
        assertEquals(listOf(PttEdgeKind.KEY, PttEdgeKind.RELEASE, PttEdgeKind.KEY, PttEdgeKind.RELEASE),
            edges.map { it.kind })
        assertEquals(listOf(1_000L, 1_100L, 1_200L, 1_300L), edges.map { it.timestampMs })

        // Defensive copy: mutating the returned list must not affect the fake.
        (edges as? MutableList<PttEdge>)?.clear()
        assertEquals(4, fake.pttEdgesSnapshot().size)
    }

    @Test
    fun catPtt_returnsConfiguredValueAndCountsInvocations() {
        val fake = FakeRigBackend()
        assertTrue(fake.catPtt(true))
        assertTrue(fake.catPtt(false))
        assertEquals(2, fake.catPttInvocations)

        fake.configureCatPttResponse(false)
        assertFalse(fake.catPtt(true))
        assertEquals(3, fake.catPttInvocations)
    }
}
