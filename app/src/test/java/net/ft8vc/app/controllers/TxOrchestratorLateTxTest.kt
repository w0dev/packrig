package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import net.ft8vc.audio.fakes.FakeUsbAudioPlayback
import net.ft8vc.ft8native.fakes.Ft8DecoderFake
import net.ft8vc.rig.fakes.FakeRigBackend
import net.ft8vc.rig.fakes.PttEdgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Integration tests for the late-start TX path wired into
 * [TxOrchestrator.transmitAfterSlotBoundary].
 *
 * Follows the [TxOrchestratorTest] real-dispatcher pattern: real
 * [Dispatchers.Default] + [runBlocking], short [slotDurationMs] for
 * watchdog safety.
 *
 * Clock is injected as a fixed lambda pinning `t_in_slot` deterministically.
 * [FakeUsbAudioPlayback] returns immediately (zero blocking-ms default) so
 * Late-path delay ([plan.waitMs] < 160 ms) is the longest real-time wait.
 *
 * For Normal and Deferred path tests, the clock is set 1 ms before the next
 * slot boundary (t_in_slot = 14 999 ms) so [SlotTiming.millisUntilNextSlot]
 * returns ≤ 1 ms — negligible wall-clock overhead.
 */
class TxOrchestratorLateTxTest {

    companion object {
        private const val SHORT_SLOT_MS = 60L
        private const val WATCHDOG_GRACE_MS_TEST = 30L

        /**
         * A slot-aligned epoch: 1_700_000_000_000 mod 15_000 = some offset;
         * subtract it to get t_in_slot = 0 at this base.
         */
        private val SLOT_START_UTC: Long = run {
            val base = 1_700_000_000_000L
            base - Math.floorMod(base, 15_000L)
        }

        /** 1 ms before next slot boundary: t_in_slot = 14 999, millisUntilNextSlot ≤ 1 ms. */
        private val NEAR_SLOT_BOUNDARY_UTC: Long = SLOT_START_UTC + 15_000L - 1L
    }

    // ── helper ─────────────────────────────────────────────────────────────

    private fun buildOrchestrator(
        decoder: Ft8DecoderFake,
        playback: FakeUsbAudioPlayback,
        rig: FakeRigBackend,
        clock: () -> Long,
        lateStartTxEnabled: Boolean,
    ): Pair<TxOrchestrator, RigSession> {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val txExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tx-late-test").also { it.isDaemon = true }
        }
        val rigSession = RigSession(
            rig = rig,
            catControl = rig,
            digirigPresenceProvider = { true },
        )
        val orchestrator = TxOrchestrator(
            decoder = decoder,
            playback = playback,
            rigSession = rigSession,
            scope = scope,
            notifyFn = { _, _ -> },
            outputDeviceIdProvider = { null },
            captureControl = object : TxOrchestrator.CaptureControl {
                override fun pauseForTx() {}
                override fun resumeAfterTx() {}
            },
            executor = txExec,
            encodeDispatcher = txExec.asCoroutineDispatcher(),
            slotDurationMs = SHORT_SLOT_MS,
            watchdogGraceMs = WATCHDOG_GRACE_MS_TEST,
            clock = clock,
            lateStartTxEnabledProvider = { lateStartTxEnabled },
        )
        return orchestrator to rigSession
    }

    // ── tests ──────────────────────────────────────────────────────────────

    /**
     * Operator taps at 6 500 ms into the slot — inside the late window [1340, 7000].
     *
     * Expected:
     *  - encode with offsetSymbols = ceil((6500 − 1180) / 160) = 34
     *  - playback with (79 − 34) × 1920 = 86 400 samples
     *  - PTT keyed and released exactly once
     */
    @Test
    fun lateTapAt6500ms_encodesWithOffset34_andPttsOnce() = runBlocking {
        val tInSlot = 6_500L
        val clock = { SLOT_START_UTC + tInSlot }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = true,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        // ceil((6500 − 1180) / 160.0) = ceil(33.25) = 34
        assertEquals("offsetSymbols should be 34", 34, enc.single().offsetSymbols)

        val plays = playback.playbackRecordsSnapshot()
        assertEquals("exactly 1 playback call", 1, plays.size)
        // Ft8DecoderFake.defaultEncodeOutput returns (79 − 34) × 1920 = 86 400 samples
        assertEquals("PCM size = (79-34)*1920", (79 - 34) * 1920, plays.single().pcm.size)

        val edges = rig.pttEdgesSnapshot()
        assertEquals("PTT keyed once", 1, edges.count { it.kind == PttEdgeKind.KEY })
        assertEquals("PTT released once", 1, edges.count { it.kind == PttEdgeKind.RELEASE })
        assertFalse("PTT must not remain keyed", rig.pttKeyed)

        orchestrator.close()
        rigSession.close()
    }

    /**
     * Clock is set 1 ms before next slot boundary (t_in_slot = 14 999 ms,
     * which is > 7 000 ms cutoff) so `computeLateTxPlan` returns Deferred.
     * The v1.0 path waits ≤ 1 ms then encodes with offsetSymbols = 0 and
     * produces a full 15 × 12 000 sample buffer.
     */
    @Test
    fun postCutoffTap_routesToDeferred_encodesWithOffset0() = runBlocking {
        // t_in_slot = 14999 > LATE_TX_CUTOFF_MS (7000) → Deferred → v1.0 path
        // millisUntilNextSlot ≤ 1 ms so the delay is negligible.
        val clock = { NEAR_SLOT_BOUNDARY_UTC }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = true,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertEquals("Deferred path: offsetSymbols=0", 0, enc.single().offsetSymbols)

        val plays = playback.playbackRecordsSnapshot()
        assertEquals("exactly 1 playback call", 1, plays.size)
        assertEquals("full slot PCM: 15*12000 samples", 15 * 12_000, plays.single().pcm.size)

        orchestrator.close()
        rigSession.close()
    }

    /**
     * With `lateStartTxEnabled = false` (the PARITY-01 escape hatch), any
     * t_in_slot — even one that would otherwise enter the late window — routes
     * through the v1.0 Normal path. Clock is set mid-window (3000 ms into slot)
     * to verify toggle precedence actually gates late-window entry, then v1.0 path
     * produces offsetSymbols = 0 as expected.
     */
    @Test
    fun toggleOff_atLateWindowTime_routesToNormal_encodesWithOffset0() = runBlocking {
        // t_in_slot = 3000 (mid-window: 1340 ≤ 3000 ≤ 7000) with toggle=false
        // → Normal path despite being in late window bounds.
        val tInSlot = 3_000L
        val clock = { SLOT_START_UTC + tInSlot }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = false,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertEquals("toggle-off Normal path: offsetSymbols=0", 0, enc.single().offsetSymbols)

        orchestrator.close()
        rigSession.close()
    }

    /**
     * Boundary: tap at exactly LATE_TX_FLOOR_MS (1 340 ms) — the inclusive
     * lower bound of the late window.
     * offsetSymbols = ceil((1340 − 1180) / 160) = ceil(1.0) = 1.
     * waitMs = (1180 + 1 × 160) − 1340 = 1340 − 1340 = 0 ms (no extra wait).
     */
    @Test
    fun tapAtFloorBoundary_offset1_zeroWait() = runBlocking {
        val tInSlot = LATE_TX_FLOOR_MS  // 1340
        val clock = { SLOT_START_UTC + tInSlot }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = true,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertEquals("offset at floor boundary = 1", 1, enc.single().offsetSymbols)

        val plays = playback.playbackRecordsSnapshot()
        assertEquals("exactly 1 playback call", 1, plays.size)
        assertEquals("PCM size = (79-1)*1920", (79 - 1) * 1920, plays.single().pcm.size)

        orchestrator.close()
        rigSession.close()
    }

    /**
     * Boundary: tap at exactly LATE_TX_CUTOFF_MS (7 000 ms) — the inclusive
     * upper bound of the late window.
     * offsetSymbols = ceil((7000 − 1180) / 160) = ceil(36.375) = 37.
     */
    @Test
    fun tapAtCutoffBoundary_offset37_stillInLateWindow() = runBlocking {
        val tInSlot = LATE_TX_CUTOFF_MS  // 7000
        val clock = { SLOT_START_UTC + tInSlot }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = true,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        assertEquals("offset at cutoff boundary = 37", 37, enc.single().offsetSymbols)

        orchestrator.close()
        rigSession.close()
    }

    /**
     * Mid-window tap at 2 800 ms: verify the correct offset is computed
     * and the PTT 4-layer defense fires (key + release, not stuck).
     * offsetSymbols = ceil((2800 − 1180) / 160) = ceil(10.125) = 11.
     */
    @Test
    fun midWindowTapAt2800ms_offset11_pttCycleComplete() = runBlocking {
        val tInSlot = 2_800L
        val clock = { SLOT_START_UTC + tInSlot }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = true,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 1 encode call", 1, enc.size)
        // ceil((2800 − 1180) / 160.0) = ceil(10.125) = 11
        assertEquals("offsetSymbols should be 11", 11, enc.single().offsetSymbols)

        val edges = rig.pttEdgesSnapshot()
        assertTrue("PTT was keyed", edges.any { it.kind == PttEdgeKind.KEY })
        assertTrue("PTT was released", edges.any { it.kind == PttEdgeKind.RELEASE })
        assertFalse("PTT must not remain stuck", rig.pttKeyed)

        orchestrator.close()
        rigSession.close()
    }

    /**
     * Drift-abort path: encode + scheduling takes >80ms, causing the
     * snapshot-to-key moment to slip past the planned symbol boundary.
     * The orchestrator detects this and falls through to the v1.0 next-slot path.
     *
     * Simulated with a two-state clock: first call (tap timestamp) returns
     * tInSlot=3000 (mid-window), second call (post-encode drift check) returns
     * +100ms later. Since 100ms > LATE_TX_DRIFT_ABORT_MS (80ms), drift-abort
     * fires and defers to the next slot with offsetSymbols=0.
     */
    @Test
    fun lateTxDriftAbortsToNextSlot_whenEncodeDelayExceeds80ms() = runBlocking {
        val tapTsMs = SLOT_START_UTC + 3_000L  // mid-window: t_in_slot = 3000
        val postEncodeTs = tapTsMs + 100L       // 100ms after tap → drift > 80ms threshold

        // Clock returns tapTsMs on first call, then postEncodeTs on all subsequent calls.
        var firstCall = true
        val clock: () -> Long = {
            if (firstCall) {
                firstCall = false
                tapTsMs
            } else {
                postEncodeTs
            }
        }

        val decoder = Ft8DecoderFake()
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val (orchestrator, rigSession) = buildOrchestrator(
            decoder, playback, rig, clock, lateStartTxEnabled = true,
        )

        val result = orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)

        assertTrue("transmit should succeed", result)

        val enc = decoder.encodeInvocationsSnapshot()
        assertEquals("exactly 2 encode calls", 2, enc.size)

        // First encode: late-start with offset computed from t_in_slot=3000
        // ceil((3000 − 1180) / 160.0) = ceil(11.375) = 12
        assertEquals("first encode offset should be 12", 12, enc[0].offsetSymbols)

        // Second encode: deferred v1.0 path after drift-abort
        assertEquals("second encode offset should be 0", 0, enc[1].offsetSymbols)

        // PTT should have been keyed exactly once (only for second encode)
        val edges = rig.pttEdgesSnapshot()
        assertEquals("PTT keyed once", 1, edges.count { it.kind == PttEdgeKind.KEY })
        assertEquals("PTT released once", 1, edges.count { it.kind == PttEdgeKind.RELEASE })
        assertFalse("PTT must not remain keyed", rig.pttKeyed)

        orchestrator.close()
        rigSession.close()
    }
}
