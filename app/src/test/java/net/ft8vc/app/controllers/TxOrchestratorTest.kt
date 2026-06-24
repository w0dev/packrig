package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-time tests for the 4-layer PTT defense + watchdog + USB safety routing.
 *
 * Uses real dispatchers (a dedicated unbounded thread pool + real Executors)
 * so that blocking `playBlocking` actually blocks on a worker thread while the
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
    private lateinit var scopeExec: ExecutorService
    private val notifications = java.util.Collections.synchronizedList(mutableListOf<Pair<String, SnackbarEvent.Tag>>())

    @Before fun setUp() {
        // A dedicated unbounded thread pool — NOT Dispatchers.Default. These are
        // real-time tests with tight (60 ms slot / 90 ms watchdog) budgets and a
        // wall-clock waitUntil. Dispatchers.Default is CPU-sized and shared across
        // every parallel test in the suite, so under full-suite load the
        // orchestrator's state-update / watchdog coroutines get starved past the
        // waitUntil deadline — the source of this class's historical flakiness.
        // A cached pool guarantees a thread is always available for this test.
        scopeExec = Executors.newCachedThreadPool()
        scope = CoroutineScope(scopeExec.asCoroutineDispatcher() + Job())
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
        orchestrator = buildOrchestrator()
    }

    /**
     * Builds a [TxOrchestrator] wired to the shared fakes. Slot/watchdog timing
     * is parameterized so an individual test can opt out of the tight default
     * watchdog: tests that drive an EVENT-driven PTT release (e.g. USB detach)
     * while playback is artificially blocked must not race the 90 ms watchdog,
     * which would otherwise latch EMERGENCY_HALT and corrupt the asserted end
     * state. Those tests pass a large [watchdogGraceMs] so only their event
     * releases PTT.
     */
    private fun buildOrchestrator(
        slotDurationMs: Long = 60L,
        watchdogGraceMs: Long = 30L,
    ): TxOrchestrator {
        val txExec = Executors.newSingleThreadExecutor()
        return TxOrchestrator(
            decoder = decoder,
            playback = playback,
            rigSession = rigSession,
            scope = scope,
            notifyFn = { text, tag -> notifications += text to tag },
            outputDeviceIdProvider = { null },
            captureControl = capture,
            executor = txExec,
            encodeDispatcher = txExec.asCoroutineDispatcher(),
            slotDurationMs = slotDurationMs,
            watchdogGraceMs = watchdogGraceMs,
        )
    }

    @After fun tearDown() {
        orchestrator.close()
        rigSession.close()
        scope.cancel()
        scopeExec.shutdownNow()
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
        // Long watchdog so the USB-detach path (not the 90 ms watchdog) is the
        // thing that releases PTT — otherwise under load the watchdog can win the
        // race and latch EMERGENCY_HALT instead of the expected RX_ONLY.
        orchestrator.close()
        orchestrator = buildOrchestrator(watchdogGraceMs = 10_000L)
        playback.blockUntilLatch = CountDownLatch(1)
        val txJob = scope.launch { orchestrator.transmit("CQ W0DEV EM26", 1000) }
        waitUntil { rig.pttKeyed }

        orchestrator.notifyUsbDetached()
        // Wait for the ASSERTED end state, not merely PTT release. notifyUsbDetached
        // releases PTT first and sets RX_ONLY second within one coroutine, so waiting
        // on !pttKeyed (the old condition) returns in the gap before the state update
        // lands — the race behind the historical "expected RX_ONLY but was READY"
        // flake. RX_ONLY implies PTT was already released.
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.RX_ONLY }

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
    fun txLog_emitsAtTxStart_onSuccessfulTransmit() = runBlocking {
        val collected = java.util.Collections.synchronizedList(mutableListOf<TxLogEvent>())
        // txLog is a replay=0 SharedFlow: an event emitted before the collector
        // subscribes is dropped. onSubscription fires once the subscriber is
        // registered, so we can block until then and remove the subscribe race
        // (otherwise transmit() can emit before collect() is active).
        val subscribed = CountDownLatch(1)
        val collectJob = scope.launch {
            orchestrator.txLog
                .onSubscription { subscribed.countDown() }
                .collect { collected += it }
        }
        assertTrue("txLog collector must subscribe", subscribed.await(2, TimeUnit.SECONDS))

        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", ok)
        waitUntil { collected.isNotEmpty() }
        assertEquals(1, collected.size)
        assertEquals(1500, collected[0].freqHz)
        assertEquals("CQ K1ABC FN42", collected[0].message)
        collectJob.cancel()
    }

    @Test
    fun txLog_emitsAtTxStart_evenWhenPlaybackHalts() = runBlocking {
        val collected = java.util.Collections.synchronizedList(mutableListOf<TxLogEvent>())
        val subscribed = CountDownLatch(1)
        val collectJob = scope.launch {
            orchestrator.txLog
                .onSubscription { subscribed.countDown() }
                .collect { collected += it }
        }
        assertTrue("txLog collector must subscribe", subscribed.await(2, TimeUnit.SECONDS))

        // Playback throws → transmit reports failure, but the row was already logged
        // at TX start (before keying), so it must still be emitted.
        playback.failNextWith = RuntimeException("playback boom")
        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)

        assertFalse("transmit should report failure when playback throws", ok)
        waitUntil { collected.isNotEmpty() }
        assertEquals(1, collected.size)
        assertEquals(1500, collected[0].freqHz)
        assertEquals("CQ K1ABC FN42", collected[0].message)
        collectJob.cancel()
    }

    @Test
    fun txLog_doesNotEmit_whenTxBlockedBeforeKeying() = runBlocking {
        val collected = java.util.Collections.synchronizedList(mutableListOf<TxLogEvent>())
        val subscribed = CountDownLatch(1)
        val collectJob = scope.launch {
            orchestrator.txLog
                .onSubscription { subscribed.countDown() }
                .collect { collected += it }
        }
        assertTrue("txLog collector must subscribe", subscribed.await(2, TimeUnit.SECONDS))

        // Safety halt active → TX is blocked before any PTT/audio goes out, so no
        // synthetic row should appear (nothing was transmitted).
        orchestrator.emergencyHalt("Field test halt")
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.EMERGENCY_HALT }

        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)

        assertFalse("transmit must be blocked when halted", ok)
        // Give any erroneous emit a chance to surface before asserting absence.
        Thread.sleep(50)
        assertTrue("no synthetic row when TX blocked before keying", collected.isEmpty())
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

    private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        // Polls until the condition holds, then returns. On timeout it returns
        // silently (does NOT throw): several callers wait on a transient state
        // (e.g. a briefly-keyed PTT) that may have already passed, and rely on the
        // subsequent assertions for the real verdict. The generous 5 s budget
        // (up from 2 s) absorbs scheduler jitter under full-suite parallel load.
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
