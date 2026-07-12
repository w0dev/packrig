package net.packset.app.controllers

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import net.packset.rig.fakes.FakeRigBackend
import net.packset.rig.fakes.PttEdgeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

class RigSessionTest {

    private lateinit var fake: FakeRigBackend
    private lateinit var session: RigSession
    private lateinit var executor: java.util.concurrent.ExecutorService

    @Before fun setUp() {
        fake = FakeRigBackend()
        executor = Executors.newSingleThreadExecutor()
        session = RigSession(
            rig = fake,
            catControl = fake,
            digirigPresenceProvider = { true },
            catTimeoutMs = 500L,
            executor = executor,
            catDispatcher = executor.asCoroutineDispatcher(),
        )
    }

    @After fun tearDown() {
        session.close()
    }

    @Test
    fun digirigPresent_reflectsProvider_atConstruction() {
        assertTrue(session.slice.value.digirigPresent)
    }

    @Test
    fun readRig_returnsFakeFrequency_andUpdatesSlice() = runTest {
        val freq = session.readRig()
        assertEquals(14_074_000L, freq)
        val slice = session.slice.value
        assertEquals(14_074_000L, slice.rigFreqHz)
        assertEquals("DATA-U", slice.rigMode)
        assertEquals("Rig in sync", slice.catStatus)
        assertFalse(slice.catBusy)
    }

    @Test
    fun setFrequency_pushesNewFreqIntoFake_andSlice() = runTest {
        val ok = session.setFrequency(7_074_000L)
        assertTrue(ok)
        assertEquals(7_074_000L, fake.currentFrequencyHz)
        assertEquals(7_074_000L, session.slice.value.rigFreqHz)
        assertEquals("Tuned", session.slice.value.catStatus)
    }

    @Test
    fun setFrequency_outsideBandLimits_isRejected_andSliceFlags() = runTest {
        val ok = session.setFrequency(100L)
        assertFalse(ok)
        assertEquals("Tune rejected", session.slice.value.catStatus)
    }

    @Test
    fun setDataMode_appliesToFake_andSlice() = runTest {
        fake.configureModeLabel("LSB")
        val ok = session.setDataMode()
        assertTrue(ok)
        assertEquals("DATA-U", fake.currentModeLabel)
        assertEquals("DATA-U", session.slice.value.rigMode)
        assertEquals("Mode set", session.slice.value.catStatus)
    }

    @Test
    fun readRig_bothChannelsTimeout_returnsNull_andRecordsFailure() = runTest {
        fake.configureTimeoutHz(true)
        fake.configureTimeoutMode(true)
        val result = session.readRig()
        assertNull(result)
        assertEquals("No CAT reply", session.slice.value.catStatus)
        assertTrue(session.consecutiveFailureCount >= 1)
    }

    @Test
    fun successfulReadRig_resetsConsecutiveFailureCount() = runTest {
        fake.configureTimeoutHz(true)
        fake.configureTimeoutMode(true)
        session.readRig()
        assertTrue(session.consecutiveFailureCount >= 1)

        val freq = session.readRig()
        assertEquals(14_074_000L, freq)
        assertEquals(0, session.consecutiveFailureCount)
    }

    @Test
    fun threeConsecutiveTimeouts_latchCatUnreachable() = runTest {
        repeat(3) {
            fake.configureTimeoutHz(true)
            fake.configureTimeoutMode(true)
            session.readRig()
        }
        assertTrue(session.slice.value.catUnreachable)
        assertEquals("CAT unreachable — tap to retry", session.slice.value.catStatus)
        // While unreachable, further CAT calls short-circuit and return null/false.
        assertNull(session.readRig())
        assertFalse(session.setFrequency(7_074_000L))
    }

    @Test
    fun retryCat_clearsUnreachable_andAllowsNextCall() = runTest {
        repeat(3) {
            fake.configureTimeoutHz(true)
            fake.configureTimeoutMode(true)
            session.readRig()
        }
        assertTrue(session.slice.value.catUnreachable)
        session.retryCat()
        assertFalse(session.slice.value.catUnreachable)
        assertEquals(0, session.consecutiveFailureCount)
        // Now real call succeeds.
        val freq = session.readRig()
        assertEquals(14_074_000L, freq)
    }

    @Test
    fun keyPtt_isIdempotent_underRepeatedCalls() = runTest {
        session.keyPtt()
        session.keyPtt()
        session.keyPtt()
        assertTrue(fake.pttKeyed)
        assertTrue(session.slice.value.pttKeyed)
        val keyEdges = fake.pttEdgesSnapshot().count { it.kind == PttEdgeKind.KEY }
        assertEquals(3, keyEdges)
    }

    @Test
    fun releasePtt_clearsKeyedFlag_andRecordsEdge() = runTest {
        session.keyPtt()
        session.releasePtt()
        assertFalse(fake.pttKeyed)
        assertFalse(session.slice.value.pttKeyed)
        val releaseEdges = fake.pttEdgesSnapshot().count { it.kind == PttEdgeKind.RELEASE }
        assertEquals(1, releaseEdges)
    }

    @Test
    fun catBusy_isClearedAfterOperation_completes() = runTest {
        session.readRig()
        assertFalse(session.slice.value.catBusy)
        session.setFrequency(14_074_000L)
        assertFalse(session.slice.value.catBusy)
    }

    @Test
    fun releasePttAsync_returnsImmediately_whileCatDispatcherIsWedged() {
        // Wedge the single CAT thread — simulates a USB serial call stuck in
        // blocking I/O (the field ANR: runBlocking release from the main thread).
        val wedge = java.util.concurrent.CountDownLatch(1)
        executor.submit { wedge.await() }

        val elapsedMs = kotlin.system.measureTimeMillis { session.releasePttAsync() }
        assertTrue(
            "releasePttAsync must not block the caller (took ${elapsedMs}ms)",
            elapsedMs < 200,
        )
        assertEquals(
            "release must not reach the rig while the dispatcher is wedged",
            0,
            fake.pttEdgesSnapshot().count { it.kind == PttEdgeKind.RELEASE },
        )

        // Once the dispatcher drains, the queued release reaches the backend.
        wedge.countDown()
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline &&
            fake.pttEdgesSnapshot().none { it.kind == PttEdgeKind.RELEASE }
        ) {
            Thread.sleep(10)
        }
        assertEquals(1, fake.pttEdgesSnapshot().count { it.kind == PttEdgeKind.RELEASE })
        assertFalse(session.slice.value.pttKeyed)
    }

    @Test
    fun setFrequency_serialisesThroughCatDispatcher() = runTest {
        val freqs = listOf(7_074_000L, 14_074_000L, 21_074_000L, 28_074_000L)
        freqs.forEach { session.setFrequency(it) }
        assertEquals(freqs.last(), session.slice.value.rigFreqHz)
        assertEquals(freqs.last(), fake.currentFrequencyHz)
    }
}
