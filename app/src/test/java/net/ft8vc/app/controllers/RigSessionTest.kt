package net.ft8vc.app.controllers

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.rig.Ft891Cat
import net.ft8vc.rig.fakes.FakeRigBackend
import net.ft8vc.rig.fakes.PttEdgeKind
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

    @Before fun setUp() {
        fake = FakeRigBackend()
        val executor = Executors.newSingleThreadExecutor()
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
    fun setMode_dataUsb_appliesToFake_andSlice() = runTest {
        fake.setMode(Ft891Cat.Mode.LSB)
        val ok = session.setMode(Ft891Cat.Mode.DATA_USB)
        assertTrue(ok)
        assertEquals(Ft891Cat.Mode.DATA_USB, fake.currentMode)
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
    fun setFrequency_serialisesThroughCatDispatcher() = runTest {
        val freqs = listOf(7_074_000L, 14_074_000L, 21_074_000L, 28_074_000L)
        freqs.forEach { session.setFrequency(it) }
        assertEquals(freqs.last(), session.slice.value.rigFreqHz)
        assertEquals(freqs.last(), fake.currentFrequencyHz)
    }
}
