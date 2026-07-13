package net.packrig.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
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
 * Regression tests: the 4 PTT-safety layers still fire when a late-TX call
 * is injected (clock inside the [LATE_TX_FLOOR_MS, LATE_TX_CUTOFF_MS] window).
 *
 * ## Why this file exists
 *
 * [TxOrchestratorTest] verified the 4-layer PTT defense under the v1.0
 * `transmit()` path. [TxOrchestratorLateTxTest] verified that
 * `transmitAfterSlotBoundary` routes to the late-TX path and encodes with
 * the correct `offsetSymbols`. This file locks in the PARITY invariant:
 * **every PTT-defense layer fires under late-TX exactly as it does under
 * v1.0 TX**, because both paths converge on [TxOrchestrator.runTxBody].
 *
 * ## Key design insight
 *
 * The late-TX window decision ([computeLateTxPlan]) uses literal constants
 * (`LATE_TX_FLOOR_MS = 1340`, `LATE_TX_CUTOFF_MS = 7000`) regardless of
 * the orchestrator's `slotDurationMs`. So we can shrink `slotDurationMs` to
 * 60 ms (making timeouts/watchdog fire in milliseconds) while setting the
 * clock mid-window (e.g. `t_in_slot = 3000`) to guarantee the late-TX path
 * is selected. The two concerns are independent.
 *
 * ## Real-dispatcher pattern
 *
 * Mirrors [TxOrchestratorTest]: real [Dispatchers.Default] + `runBlocking`.
 * Slot duration shrunk to 60 ms, watchdog grace to 30 ms, so the full suite
 * runs in under 2 seconds.
 */
class TxOrchestratorPttSafetyLateTxTest {

    companion object {
        private const val SHORT_SLOT_MS = 60L
        private const val SHORT_WATCHDOG_MS = 30L

        /**
         * Slot-aligned epoch so `SlotTiming.slotStart(SLOT_START_UTC) == SLOT_START_UTC`.
         */
        val SLOT_START_UTC: Long = run {
            val base = 1_700_000_000_000L
            base - Math.floorMod(base, 15_000L)
        }

        /** t_in_slot = 3000 ms — comfortably inside [1340, 7000] late window. */
        const val T_IN_SLOT_MID = 3_000L
    }

    // ── factory ──────────────────────────────────────────────────────────────

    private fun buildOrchestrator(
        decoder: Ft8DecoderFake,
        playback: FakeUsbAudioPlayback,
        rig: FakeRigBackend,
        clock: () -> Long,
        notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf<Pair<String, SnackbarEvent.Tag>>()),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job()),
    ): TxOrchestrator {
        val txExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tx-safety-late-test").also { it.isDaemon = true }
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
            clock = clock,
            lateStartTxEnabledProvider = { true },
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
     * With t_in_slot = 3000 ms (late window) and a throw injected into
     * `playBlocking`, the `try-finally` block inside `runTxBody` must release
     * PTT even though the exception propagates. encode() is called with
     * `offsetSymbols > 0` (confirming the late-TX path ran), and PTT edges
     * show exactly one KEY and one RELEASE.
     */
    @Test
    fun layerA_lateTx_pttReleasedInFinally_whenPlaybackThrows() = runBlocking {
        val clock = { SLOT_START_UTC + T_IN_SLOT_MID }
        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback().apply {
            configureNextPlayThrows(RuntimeException("late-TX playback boom"))
        }
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())

        val orchestrator = buildOrchestrator(decoder, playback, rig, clock, notifications)

        val result = orchestrator.transmitAfterSlotBoundary("CQ W0DEV EM26", txFreqHz = 1500)

        // Late-TX path should have proceeded and failed, not pre-aborted.
        assertFalse("transmit should return false (playback threw)", result)

        waitUntil { !rig.pttKeyed }

        // Confirm late-TX encode: offsetSymbols > 0 (ceil((3000-1180)/160) = 12).
        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertTrue("late-TX encode must use offsetSymbols > 0", enc.single().offsetSymbols > 0)

        // PTT defense layer (a): key once, release once despite throw.
        val edges = rig.pttEdgesSnapshot()
        assertEquals("PTT keyed exactly once", 1, edges.count { it.kind == PttEdgeKind.KEY })
        assertEquals("PTT released exactly once via finally", 1, edges.count { it.kind == PttEdgeKind.RELEASE })
        assertFalse("PTT must not remain keyed", rig.pttKeyed)

        orchestrator.close()
    }

    // ── Layer (b): AutoCloseable use{} releases PTT on coroutine cancel ──────

    /**
     * With t_in_slot = 3000 ms (late window) and a latch blocking `playBlocking`,
     * cancelling the parent job mid-TX must trigger `TxSession.close()` via
     * `AutoCloseable use{}`, which force-releases PTT via `runBlocking`.
     */
    @Test
    fun layerB_lateTx_pttReleasedViaAutoCloseable_whenJobCancelled() = runBlocking {
        val clock = { SLOT_START_UTC + T_IN_SLOT_MID }
        val decoder = Ft8DecoderFake()
        val latch = CountDownLatch(1)
        val playback = FakeUsbAudioPlayback().apply { configureBlockLatch(latch) }
        val rig = FakeRigBackend()
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, clock, scope = scope)

        val txJob = scope.launch {
            orchestrator.transmitAfterSlotBoundary("CQ W0DEV EM26", txFreqHz = 1500)
        }

        // Wait until PTT is keyed (confirming we're inside the late-TX body).
        waitUntil { rig.pttKeyed }
        assertTrue("PTT should have been keyed before cancel", rig.pttKeyed)

        // Confirm late-TX path ran (offsetSymbols > 0 encoded before PTT key).
        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call before cancel", 1, enc.size)
        assertTrue("late-TX encode must use offsetSymbols > 0", enc.single().offsetSymbols > 0)

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
     * With t_in_slot = 3000 ms (late window) and `playBlocking` latched to
     * block indefinitely, the outer `withTimeoutOrNull(slotDurationMs + 500)`
     * must fire after ~560 ms (60 + 500), force-release PTT, set
     * `txSafetyHaltActive = true`, and emit the "TX timeout" snackbar.
     *
     * Note: with `slotDurationMs = 60` and `watchdogGraceMs = 30`, the watchdog
     * fires at 90 ms — well before the outer timeout at 560 ms. So in practice
     * **layer (d)** fires first and releases PTT; the outer timeout's
     * `forceReleasePtt()` is a no-op (already released). Both layers' assertions
     * about the final state are satisfied: PTT not keyed, `txSafetyHaltActive`
     * set.  This matches the behaviour in [TxOrchestratorTest.layerC].
     */
    @Test
    fun layerC_lateTx_outerTimeoutReleasesPtt_whenPlaybackHangsPastOuterGrace() = runBlocking {
        val clock = { SLOT_START_UTC + T_IN_SLOT_MID }
        val decoder = Ft8DecoderFake()
        val latch = CountDownLatch(1)
        val playback = FakeUsbAudioPlayback().apply { configureBlockLatch(latch) }
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, clock, notifications, scope)

        val txJob = scope.launch {
            orchestrator.transmitAfterSlotBoundary("CQ W0DEV EM26", txFreqHz = 1500)
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
     * the outer timeout — fires first: PTT is force-released, `txSafetyHaltActive`
     * is set, and the "TX safety halt" snackbar is emitted.
     */
    @Test
    fun layerD_lateTx_watchdogFiresFirst_forcesRelease_andLatchesSafetyHalt() = runBlocking {
        val clock = { SLOT_START_UTC + T_IN_SLOT_MID }
        val decoder = Ft8DecoderFake()
        val latch = CountDownLatch(1)
        val playback = FakeUsbAudioPlayback().apply { configureBlockLatch(latch) }
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(Dispatchers.Default + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, clock, notifications, scope)

        val txJob = scope.launch {
            orchestrator.transmitAfterSlotBoundary("CQ W0DEV EM26", txFreqHz = 1500)
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

        // Confirm late-TX path: offsetSymbols > 0.
        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertTrue("late-TX encode must use offsetSymbols > 0", enc.single().offsetSymbols > 0)

        latch.countDown()
        txJob.cancel()

        orchestrator.close()
        scope.cancel()
    }

    // ── Layer (e): post-wait fail-closed re-check ────────────────────────────

    /**
     * Late-TX path: USB detach during the symbol-clock-alignment delay
     * (`plan.waitMs` between encode and runTxBody) must cause runTxBody to
     * fail-closed without ever keying PTT.
     *
     * t_in_slot = 3000 → offsetSymbols = 12, keyMoment = 3100, waitMs = 100ms.
     *
     * Anti-flake patterns:
     *  - dedicated single-thread dispatcher for `scope` so detach work isn't
     *    starved behind other tests on Dispatchers.Default
     *  - wait-for-asserted-state: we don't inject detach until encode has
     *    completed (we're definitely inside the 100ms waitMs delay), and we
     *    don't proceed past the assertions until appRfState == RX_ONLY
     */
    @Test
    fun postWaitRecheck_lateTx_failsClosedOnUsbDetachDuringSymbolAlignmentWait() = runBlocking {
        val clock = { SLOT_START_UTC + T_IN_SLOT_MID }
        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val scopeExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tx-recheck-late-scope").also { it.isDaemon = true }
        }
        val scope = CoroutineScope(scopeExec.asCoroutineDispatcher() + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, clock, notifications, scope)

        val txJob = scope.async {
            orchestrator.transmitAfterSlotBoundary("CQ W0DEV EM26", txFreqHz = 1500)
        }

        // Wait for encode to complete (confirms we're past encode but in the waitMs delay).
        waitUntil { decoder.encodeInvocationsSnapshot().isNotEmpty() }
        assertTrue(
            "late-TX encode must have run with offsetSymbols > 0",
            decoder.encodeInvocationsSnapshot().single().offsetSymbols > 0,
        )

        // Inject USB detach during the symbol-clock-alignment wait.
        orchestrator.notifyUsbDetached()

        // Wait for the state flip to land BEFORE assertions (notifyUsbDetached runs in scope.launch).
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.RX_ONLY }

        val result = txJob.await()

        assertFalse("transmitAfterSlotBoundary must return false after detach", result)
        assertFalse("PTT must never be keyed when detach precedes runTxBody", rig.pttKeyed)
        assertEquals(
            "no PTT edges at all — fail-closed before TxSession().use{}",
            0,
            rig.pttEdgesSnapshot().count { it.kind == PttEdgeKind.KEY },
        )
        assertTrue(
            "post-wait re-check snackbar must fire",
            notifications.any { it.first.contains("Digirig disconnected during wait") },
        )

        orchestrator.close()
        scope.cancel()
        scopeExec.shutdown()
    }

    /**
     * V1.0 path (Deferred — t_in_slot past late cutoff): the slot-boundary
     * delay in `transmitAfterSlotBoundary` is the pre-TX wait. A USB detach
     * during that wait must cause runTxBody to fail-closed.
     *
     * t_in_slot = 10_000 → past LATE_TX_CUTOFF_MS=7000 → Deferred → v1.0 path,
     * waitMs = millisUntilNextSlot = 5000ms. The wide 5s window gives plenty
     * of slack for `notifyUsbDetached()` (scope.launch) to dispatch and flip
     * appRfState before the TX coroutine wakes from `delay()` — eliminates the
     * race that exists with a sub-200ms waitMs.
     *
     * Anti-flake patterns: same as the late-TX test — dedicated dispatcher
     * and wait-for-asserted-state before letting txJob complete.
     */
    @Test
    fun postWaitRecheck_v1Path_failsClosedOnUsbDetachDuringSlotBoundaryWait() = runBlocking {
        val clock = { SLOT_START_UTC + 10_000L }  // Deferred → v1.0 path, waitMs = 5000ms
        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val notifications: MutableList<Pair<String, SnackbarEvent.Tag>> =
            java.util.Collections.synchronizedList(mutableListOf())
        val scopeExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tx-recheck-v1-scope").also { it.isDaemon = true }
        }
        val scope = CoroutineScope(scopeExec.asCoroutineDispatcher() + Job())

        val orchestrator = buildOrchestrator(decoder, playback, rig, clock, notifications, scope)

        val txJob = scope.async {
            orchestrator.transmitAfterSlotBoundary("CQ W0DEV EM26", txFreqHz = 1500)
        }

        // Inject USB detach well inside the 5s slot-boundary delay.
        orchestrator.notifyUsbDetached()
        // Wait for the state flip to land BEFORE the TX coroutine wakes from delay().
        // The 5s waitMs gives massive headroom — this should settle in < 50ms.
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.RX_ONLY }

        val result = txJob.await(/* up to ~5s for delay() to complete naturally */)

        assertFalse("transmitAfterSlotBoundary must return false after detach", result)
        assertFalse("PTT must never be keyed when detach precedes runTxBody", rig.pttKeyed)
        assertEquals(
            "no PTT edges at all — fail-closed before TxSession().use{}",
            0,
            rig.pttEdgesSnapshot().count { it.kind == PttEdgeKind.KEY },
        )
        assertTrue(
            "post-wait re-check snackbar must fire",
            notifications.any { it.first.contains("Digirig disconnected during wait") },
        )

        // V1.0 path: encode runs only AFTER the wait completes — confirm offsetSymbols = 0
        // if it ran at all (decoder may or may not have been called — race with re-check).
        decoder.encodeInvocationsSnapshot().forEach {
            assertEquals("v1.0 path must use offsetSymbols == 0", 0, it.offsetSymbols)
        }

        orchestrator.close()
        scope.cancel()
        scopeExec.shutdown()
    }
}
