package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.ft8vc.app.SnackbarEvent
import net.ft8vc.audio.UsbPlaybackApi
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import net.ft8vc.rig.fakes.FakeRigBackend
import net.ft8vc.rig.fakes.PttEdgeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-time tests for the 4-layer PTT defense + watchdog + USB safety routing.
 *
 * Uses real dispatchers (Dispatchers.Default + real Executors) so that
 * blocking `playBlocking` actually blocks on a worker thread while the
 * test thread observes PTT state and triggers the watchdog/timeout paths.
 * Slot duration is shrunk to 60 ms with a 30 ms watchdog grace so the
 * entire suite runs in well under a second.
 */
class TxOrchestratorTest {

    private lateinit var scope: CoroutineScope
    private lateinit var rig: FakeRigBackend
    private lateinit var rigSession: RigSession
    private lateinit var playback: ScriptablePlayback
    private lateinit var decoder: ScriptableDecoder
    private lateinit var capture: TestCaptureControl
    private lateinit var orchestrator: TxOrchestrator
    private val notifications = java.util.Collections.synchronizedList(mutableListOf<Pair<String, SnackbarEvent.Tag>>())

    @Before fun setUp() {
        scope = CoroutineScope(Dispatchers.Default + Job())
        rig = FakeRigBackend()
        rigSession = RigSession(
            rig = rig,
            catControl = rig,
            digirigPresenceProvider = { true },
        )
        playback = ScriptablePlayback()
        decoder = ScriptableDecoder()
        capture = TestCaptureControl()
        notifications.clear()
        val txExec = Executors.newSingleThreadExecutor()
        orchestrator = TxOrchestrator(
            decoder = decoder,
            playback = playback,
            rigSession = rigSession,
            scope = scope,
            notifyFn = { text, tag -> notifications += text to tag },
            outputDeviceIdProvider = { null },
            captureControl = capture,
            executor = txExec,
            encodeDispatcher = txExec.asCoroutineDispatcher(),
            slotDurationMs = 60L,
            watchdogGraceMs = 30L,
        )
    }

    @After fun tearDown() {
        orchestrator.close()
        rigSession.close()
        scope.cancel()
    }

    // ── Layer (a) try-finally ───────────────────────────────────────────

    @Test
    fun layerA_pttReleased_whenPlaybackThrows() = runBlocking {
        playback.failNextWith = RuntimeException("playback boom")
        val ok = orchestrator.transmit("CQ W0DEV EM26", 1000)
        assertFalse(ok)
        waitUntil { !rig.pttKeyed }
        assertFalse(rig.pttKeyed)
        val edges = rig.pttEdgesSnapshot()
        assertEquals(1, edges.count { it.kind == PttEdgeKind.KEY })
        assertEquals(1, edges.count { it.kind == PttEdgeKind.RELEASE })
    }

    // ── Layer (b) AutoCloseable use{} ───────────────────────────────────

    @Test
    fun layerB_pttReleased_whenCallerCancelledMidTx() = runBlocking {
        val txJob = scope.launch { orchestrator.transmit("CQ W0DEV EM26", 1000) }
        waitUntil { rig.pttKeyed }
        txJob.cancel()
        playback.releaseBlock()
        waitUntil { !rig.pttKeyed }
        assertFalse(rig.pttKeyed)
        assertTrue(rig.pttEdgesSnapshot().any { it.kind == PttEdgeKind.RELEASE })
    }

    // ── Layer (c) withTimeoutOrNull (outer 500 ms grace beyond slot) ───

    @Test
    fun layerC_outerTimeout_releasesPtt_whenPlaybackHangsPastOuterGrace() = runBlocking {
        playback.blockUntilLatch = CountDownLatch(1)
        val txJob = scope.launch { orchestrator.transmit("CQ W0DEV EM26", 1000) }
        waitUntil { rig.pttKeyed }
        // Slot=60ms + outer grace=500ms = ~560ms before outer timeout fires.
        // The watchdog (60+30=90ms) will fire first and release PTT.
        Thread.sleep(700L)
        playback.releaseBlock()
        txJob.cancel()
        waitUntil { !rig.pttKeyed }
        assertFalse(rig.pttKeyed)
    }

    // ── Layer (d) 250 ms watchdog ───────────────────────────────────────

    @Test
    fun layerD_watchdog_forcesRelease_andLatchesSafetyHalt() = runBlocking {
        playback.blockUntilLatch = CountDownLatch(1)
        val txJob = scope.launch { orchestrator.transmit("CQ W0DEV EM26", 1000) }
        waitUntil { rig.pttKeyed }
        // Slot+watchdog grace = 90ms. Sleep beyond it.
        Thread.sleep(200L)
        // Watchdog should have fired.
        assertFalse(rig.pttKeyed)
        assertTrue(orchestrator.slice.value.txSafetyHaltActive)
        assertTrue(notifications.any { it.first.contains("safety halt") })
        playback.releaseBlock()
        txJob.cancel()
    }

    // ── USB detach mid-TX ───────────────────────────────────────────────

    @Test
    fun usbDetachMidTx_transitionsToRxOnly_andReleasesPtt() = runBlocking {
        playback.blockUntilLatch = CountDownLatch(1)
        val txJob = scope.launch { orchestrator.transmit("CQ W0DEV EM26", 1000) }
        waitUntil { rig.pttKeyed }

        orchestrator.notifyUsbDetached()
        waitUntil { !rig.pttKeyed }

        assertEquals(AppRfState.RX_ONLY, orchestrator.slice.value.appRfState)
        assertTrue(orchestrator.slice.value.digirigDisconnected)
        assertFalse(rig.pttKeyed)

        playback.releaseBlock()
        txJob.cancel()
    }

    @Test
    fun txRejected_whenAppRfStateIsRxOnly() = runBlocking {
        orchestrator.notifyUsbDetached()
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.RX_ONLY }
        val ok = orchestrator.transmit("CQ W0DEV EM26", 1000)
        assertFalse(ok)
        assertEquals(0, rig.pttEdgesSnapshot().count { it.kind == PttEdgeKind.KEY })
    }

    @Test
    fun txRejected_whenAppRfStateIsEmergencyHalt() = runBlocking {
        orchestrator.emergencyHalt("Test halt")
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.EMERGENCY_HALT }
        val ok = orchestrator.transmit("CQ W0DEV EM26", 1000)
        assertFalse(ok)
    }

    @Test
    fun acknowledgeAndResetEmergency_returnsToReady() = runBlocking {
        orchestrator.emergencyHalt("test")
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.EMERGENCY_HALT }
        orchestrator.acknowledgeAndResetEmergency()
        assertEquals(AppRfState.READY, orchestrator.slice.value.appRfState)
        assertFalse(orchestrator.slice.value.txSafetyHaltActive)
    }

    @Test
    fun notifyUsbReady_onlyTransitions_fromRxOnly_notFromEmergencyHalt() = runBlocking {
        orchestrator.notifyUsbDetached()
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.RX_ONLY }
        orchestrator.emergencyHalt("test")
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.EMERGENCY_HALT }
        orchestrator.notifyUsbReady()
        assertEquals(AppRfState.EMERGENCY_HALT, orchestrator.slice.value.appRfState)
    }

    @Test
    fun txRejected_whenDecoderNotAvailable() = runBlocking {
        decoder.available = false
        val ok = orchestrator.transmit("CQ W0DEV EM26", 1000)
        assertFalse(ok)
        assertTrue(notifications.any { it.first.contains("Decoder library") })
    }

    @Test
    fun cleanTx_releasesPtt_andResumesCapture() = runBlocking {
        val ok = orchestrator.transmit("CQ W0DEV EM26", 1000)
        assertTrue(ok)
        assertTrue(capture.wasPaused)
        assertTrue(capture.wasResumed)
        assertFalse(rig.pttKeyed)
    }

    @Test
    fun txLog_emits_on_successful_transmit() = runBlocking {
        val collected = java.util.Collections.synchronizedList(mutableListOf<TxLogEvent>())
        val collectJob = scope.launch { orchestrator.txLog.collect { collected += it } }

        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", ok)
        waitUntil { collected.isNotEmpty() }
        assertEquals(1, collected.size)
        assertEquals(1500, collected[0].freqHz)
        assertEquals("CQ K1ABC FN42", collected[0].message)
        collectJob.cancel()
    }

    @Test
    fun emergencyHalt_recordsSafetyHaltLatched() = runBlocking {
        orchestrator.emergencyHalt("Field test halt")
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.EMERGENCY_HALT }
        assertTrue(orchestrator.slice.value.txSafetyHaltActive)
        assertEquals(AppRfState.EMERGENCY_HALT, orchestrator.slice.value.appRfState)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
    }

    private class ScriptablePlayback : UsbPlaybackApi {
        @Volatile var failNextWith: Throwable? = null
        @Volatile var blockUntilLatch: CountDownLatch? = null

        override fun playBlocking(samples12k: ShortArray, preferredDeviceId: Int?): Boolean {
            failNextWith?.let { failNextWith = null; throw it }
            blockUntilLatch?.let { latch ->
                latch.await(5, TimeUnit.SECONDS)
                return false
            }
            return true
        }

        override fun stop() {
            blockUntilLatch?.countDown()
        }

        fun releaseBlock() = stop()
    }

    private class ScriptableDecoder : Ft8DecoderApi {
        @Volatile var available: Boolean = true
        @Volatile var encodeResult: ShortArray = ShortArray(180_000) { 1000 }
        override fun isAvailable(): Boolean = available
        override fun version(): String = "fake-1.0"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> = emptyArray()
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray = encodeResult
    }

    private class TestCaptureControl : TxOrchestrator.CaptureControl {
        @Volatile var wasPaused: Boolean = false
        @Volatile var wasResumed: Boolean = false
        override fun pauseForTx() { wasPaused = true }
        override fun resumeAfterTx() { wasResumed = true }
    }
}
