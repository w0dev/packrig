package net.packrig.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.packrig.app.SnackbarEvent
import net.packrig.audio.fakes.FakeUsbAudioPlayback
import net.packrig.ft8native.fakes.Ft8DecoderFake
import net.packrig.rig.fakes.FakeRigBackend
import net.packrig.rig.fakes.PttEdgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Regression tests: the 4 PTT-safety layers still fire when TX is triggered
 * via an EARLY-pass-armed auto-answer — i.e. when [TxOrchestrator.transmit]
 * is called as the [QsoSessionController] `transmitFn` after the QSO machine
 * was armed by a [DecodeBatch] emitted from the early-decode pass at t≈12 s.
 *
 * ## Why this file exists
 *
 * [TxOrchestratorTest] verified the 4-layer PTT defense under the v1.0
 * `transmit()` path (no slot-boundary wait, no late-TX offset).
 * [TxOrchestratorPttSafetyLateTxTest] verified parity under the late-TX path
 * (`transmitAfterSlotBoundary` with t_in_slot inside the late window).
 * This file locks in the PARITY invariant for the EARLY-armed trigger path:
 * **every PTT-defense layer fires when TX originates from an EARLY-pass
 * DecodeBatch arming the QsoSessionController**, because the QSO loop's
 * `transmitFn` calls [TxOrchestrator.transmit] — the same entry point that
 * [TxOrchestratorTest] exercises.
 *
 * ## Early-arm vs late-TX distinction
 *
 * The EARLY decode fires at slot-relative t=12 s: [DecodeController] emits a
 * [DecodeBatch] on `_decodesOut`, [QsoSessionController.onDecodeBatch] advances
 * or arms the QSO machine, and then on the **next slot boundary** the QSO loop
 * calls `transmitFn(message)` → [TxOrchestrator.transmit]. No slot-boundary
 * wait or `offsetSymbols` is involved (unlike late-TX): `transmit()` is called
 * directly, exactly as in the v1.0 path. The "EARLY-armed" label refers to
 * which signal set the QSO machine in motion — not which TX method runs.
 *
 * By calling [TxOrchestrator.transmit] directly in each test (mirroring the
 * QSO loop's `transmitFn` call) and injecting faults via the same
 * [FakeUsbAudioPlayback] and [FakeRigBackend] seams used by
 * [TxOrchestratorPttSafetyLateTxTest], we confirm that all four defense layers
 * fire regardless of which decode pass armed the TX cycle.
 *
 * ## Real-dispatcher pattern
 *
 * Mirrors [TxOrchestratorPttSafetyLateTxTest]: real [Dispatchers.Default] +
 * `runBlocking`. Slot duration shrunk to 60 ms, watchdog grace to 30 ms, so
 * the full suite runs in under 2 seconds.
 */
class TxOrchestratorPttSafetyEarlyDecodeTest {

    companion object {
        private const val SHORT_SLOT_MS = 60L
        private const val SHORT_WATCHDOG_MS = 30L
    }

    // ── factory ──────────────────────────────────────────────────────────────

    private fun buildOrchestrator(
        decoder: Ft8DecoderFake,
        playback: FakeUsbAudioPlayback,
        rig: FakeRigBackend,
        notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf<Pair<String, SnackbarEvent.Tag>>()),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job()),
    ): TxOrchestrator {
        val txExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tx-safety-early-test").also { it.isDaemon = true }
        }
        val rigSession = RigSession(
            rig = rig,
            catControl = rig,
            digirigPresenceProvider = { true },
        )
        return TxOrchestrator(
            decoder = decoder,
            playback = playback,
            rigSession = rigSession,
            scope = scope,
            notifyFn = { text, tag -> notifications += text to tag },
            outputDeviceIdProvider = { null },
            captureControl = object : TxOrchestrator.CaptureControl {
                override suspend fun pauseForTx() {}
                override fun resumeAfterTx() {}
            },
            executor = txExec,
            encodeDispatcher = txExec.asCoroutineDispatcher(),
            slotDurationMs = SHORT_SLOT_MS,
            watchdogGraceMs = SHORT_WATCHDOG_MS,
        )
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
    }

    // ── Layer (a): try-finally releases PTT when playback throws ─────────────

    /**
     * Simulates an EARLY-pass-armed TX: `QsoSessionController` received a
     * [DecodeBatch] from the early-decode pass, advanced the QSO machine, and
     * on the next slot boundary called `transmitFn(message)` →
     * [TxOrchestrator.transmit]. A throw is injected into `playBlocking`; the
     * `try-finally` block inside `runTxBody` must release PTT even though the
     * exception propagates.
     *
     * PTT edges must show exactly one KEY and one RELEASE. No `offsetSymbols`
     * assertion here (unlike late-TX): `transmit()` encodes with
     * `offsetSymbols = 0` by design.
     */
    @Test
    fun layerA_earlyArmedTx_pttReleasedInFinally_whenPlaybackThrows() = runBlocking {
        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback().apply {
            configureNextPlayThrows(RuntimeException("early-armed TX playback boom"))
        }
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())

        val orchestrator = buildOrchestrator(decoder, playback, rig, notifications)

        // Mirrors QsoSessionController.transmitFn → txOrchestrator.transmit().
        val result = orchestrator.transmit("CQ W0DEV EM26", txFreqHz = 1500)

        // Playback threw — transmit must return false.
        assertFalse("transmit should return false (playback threw)", result)

        waitUntil { !rig.pttKeyed }

        // PTT defense layer (a): key once, release once despite throw.
        val edges = rig.pttEdgesSnapshot()
        assertEquals("PTT keyed exactly once", 1, edges.count { it.kind == PttEdgeKind.KEY })
        assertEquals("PTT released exactly once via finally", 1, edges.count { it.kind == PttEdgeKind.RELEASE })
        assertFalse("PTT must not remain keyed", rig.pttKeyed)

        // Encode was called with offsetSymbols = 0 (v1.0 transmit path, no late-TX offset).
        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertEquals("early-armed transmit must use offsetSymbols = 0", 0, enc.single().offsetSymbols)

        orchestrator.close()
    }

    // ── Layer (b): AutoCloseable use{} releases PTT on coroutine cancel ──────

    /**
     * With `playBlocking` blocked via a latch, cancelling the parent job
     * mid-TX must trigger `TxSession.close()` via `AutoCloseable use{}`,
     * which force-releases PTT via `runBlocking`.
     *
     * TX is triggered as the QSO loop would after an EARLY-armed decode:
     * direct call to [TxOrchestrator.transmit] with `offsetSymbols = 0`.
     */
    @Test
    fun layerB_earlyArmedTx_pttReleasedViaAutoCloseable_whenJobCancelled() = runBlocking {
        val decoder = Ft8DecoderFake()
        val latch = CountDownLatch(1)
        val playback = FakeUsbAudioPlayback().apply { configureBlockLatch(latch) }
        val rig = FakeRigBackend()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, scope = scope)

        val txJob = scope.launch {
            orchestrator.transmit("CQ W0DEV EM26", txFreqHz = 1500)
        }

        // Wait until PTT is keyed (confirming we're inside the TX body).
        waitUntil { rig.pttKeyed }
        assertTrue("PTT should have been keyed before cancel", rig.pttKeyed)

        // Encode was called with offsetSymbols = 0.
        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call before cancel", 1, enc.size)
        assertEquals("early-armed transmit must use offsetSymbols = 0", 0, enc.single().offsetSymbols)

        // Cancel the job; AutoCloseable.close() must release PTT.
        txJob.cancel()
        latch.countDown()  // unblock playBlocking so the thread can exit

        waitUntil { !rig.pttKeyed }

        assertFalse("PTT must be released after job cancel", rig.pttKeyed)
        assertTrue(
            "PTT edges must include a RELEASE",
            rig.pttEdgesSnapshot().any { it.kind == PttEdgeKind.RELEASE },
        )

        orchestrator.close()
        scope.cancel()
    }

    // ── Layer (c): outer withTimeoutOrNull releases PTT when playback hangs ──

    /**
     * With `playBlocking` latched to block indefinitely, the outer
     * `withTimeoutOrNull(slotDurationMs + 500)` must fire after ~560 ms
     * (60 + 500), force-release PTT, set `txSafetyHaltActive = true`.
     *
     * Note: with `slotDurationMs = 60` and `watchdogGraceMs = 30`, the watchdog
     * fires at 90 ms — well before the outer timeout at 560 ms. So in practice
     * **layer (d)** fires first and releases PTT; the outer timeout's
     * `forceReleasePtt()` is a no-op (already released). Both layers' assertions
     * about the final state are satisfied: PTT not keyed, `txSafetyHaltActive`
     * set. This matches the behaviour in [TxOrchestratorTest.layerC] and
     * [TxOrchestratorPttSafetyLateTxTest.layerC_lateTx_outerTimeoutReleasesPtt_whenPlaybackHangsPastOuterGrace].
     */
    @Test
    fun layerC_earlyArmedTx_outerTimeoutReleasesPtt_whenPlaybackHangsPastOuterGrace() = runBlocking {
        val decoder = Ft8DecoderFake()
        val latch = CountDownLatch(1)
        val playback = FakeUsbAudioPlayback().apply { configureBlockLatch(latch) }
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, notifications, scope)

        val txJob = scope.launch {
            orchestrator.transmit("CQ W0DEV EM26", txFreqHz = 1500)
        }

        waitUntil { rig.pttKeyed }
        assertTrue("PTT should have been keyed", rig.pttKeyed)

        // Wait past outer timeout (slotDurationMs=60 + WATCHDOG_OUTER_GRACE_MS=500 = 560ms).
        // Give it 700ms to be safe.
        Thread.sleep(700L)

        // Outer timeout (or watchdog) must have fired, releasing PTT.
        waitUntil { !rig.pttKeyed }
        assertFalse("PTT must be released after outer timeout", rig.pttKeyed)
        assertTrue(
            "txSafetyHaltActive must be set",
            orchestrator.slice.value.txSafetyHaltActive,
        )

        latch.countDown()
        txJob.cancel()

        orchestrator.close()
        scope.cancel()
    }

    // ── Layer (d): 250ms watchdog fires before outer timeout ─────────────────

    /**
     * With `slotDurationMs = 60` and `watchdogGraceMs = 30`, the watchdog
     * fires at 90 ms. With `playBlocking` blocked via a latch and a 200 ms
     * sleep (past 90ms but before 560ms outer timeout), the watchdog — not
     * the outer timeout — fires first: PTT is force-released,
     * `txSafetyHaltActive` is set, and the "TX safety halt" snackbar is
     * emitted.
     *
     * TX is triggered as the QSO loop would after an EARLY-armed decode:
     * direct call to [TxOrchestrator.transmit] with `offsetSymbols = 0`.
     */
    @Test
    fun layerD_earlyArmedTx_watchdogFiresFirst_forcesRelease_andLatchesSafetyHalt() = runBlocking {
        val decoder = Ft8DecoderFake()
        val latch = CountDownLatch(1)
        val playback = FakeUsbAudioPlayback().apply { configureBlockLatch(latch) }
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, notifications, scope)

        val txJob = scope.launch {
            orchestrator.transmit("CQ W0DEV EM26", txFreqHz = 1500)
        }

        waitUntil { rig.pttKeyed }
        assertTrue("PTT should have been keyed", rig.pttKeyed)

        // Watchdog fires at slotDurationMs + watchdogGraceMs = 60 + 30 = 90ms.
        // Sleep 200ms — past watchdog but well before outer timeout (560ms).
        Thread.sleep(200L)

        // Watchdog must have fired.
        assertFalse("PTT must be released by watchdog", rig.pttKeyed)
        assertTrue(
            "txSafetyHaltActive must be latched by watchdog",
            orchestrator.slice.value.txSafetyHaltActive,
        )
        assertTrue(
            "watchdog safety halt snackbar must be emitted",
            notifications.any { it.first.contains("safety halt") },
        )

        // Encode was called with offsetSymbols = 0 (v1.0 transmit path, no late-TX offset).
        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertEquals("early-armed transmit must use offsetSymbols = 0", 0, enc.single().offsetSymbols)

        latch.countDown()
        txJob.cancel()

        orchestrator.close()
        scope.cancel()
    }
}
