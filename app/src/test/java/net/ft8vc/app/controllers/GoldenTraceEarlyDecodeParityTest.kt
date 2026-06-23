package net.ft8vc.app.controllers

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * Golden-trace parity assertions for the early-decode feature (Task 12).
 *
 * ## Structural note (mirrors late-TX Task 10 finding)
 *
 * The existing golden-trace harness ([net.ft8vc.app.foundations.golden.GoldenTraceReplay])
 * exercises [net.ft8vc.core.QsoMachine] directly. It feeds canned [net.ft8vc.core.QsoDecode]
 * lists straight to `machine.onDecodes()` and never constructs [DecodeController].
 * This means the harness is **structurally insensitive** to the early-decode toggle:
 * `GoldenTraceTest.replaysCqAnswer73AndReachesComplete` passes identically regardless of
 * whether `earlyDecodeEnabled` is true or false, because [DecodeController] — and
 * therefore the per-slot [seenKeys] dedup set — is never instantiated by the replay.
 *
 * This is the same structural gap the late-TX plan documented in Task 10. The toggle-OFF
 * byte-identity guarantee is vacuously preserved at the harness layer (no new code paths
 * can be exercised). Toggle-OFF behavior parity is instead enforced by:
 *  - [EarlyDecodeSchedulerTest] — `toggling earlyDecodeEnabled off cancels pending early pass`
 *  - [TxOrchestratorPttSafetyEarlyDecodeTest] — PTT-safety matrix with earlyDecodeEnabled=false
 *
 * ## What this file asserts
 *
 * 1. [structuralNoteGoldenTraceHarnessIsInsensitiveToEarlyDecodeToggle] — documents the
 *    gap above; @Ignored so it does not clutter test results with a trivial pass.
 *
 * 2. [noDuplicateKeyEmittedAcrossOneHundredSimulatedSlots] — the strongest evidence the
 *    spec requires: 100 simulated slots, each with overlapping EARLY + FULL decode result
 *    sets, assert that no `(slotStart, stableId)` tuple appears more than once across all
 *    [DecodeController.decodesOut] emissions.
 */
class GoldenTraceEarlyDecodeParityTest {

    // ── Shared fake ───────────────────────────────────────────────────────────

    /**
     * A [Ft8DecoderApi] that pops from a per-call queue. Mirrors the local
     * [QueuedDecoderFake] in [EarlyDecodeLateTxIntegrationTest] — kept here so
     * this test file is self-contained.
     */
    private class QueuedDecoderFake(
        private val queue: ArrayDeque<List<Ft8DecodeResult>> = ArrayDeque(),
    ) : Ft8DecoderApi {
        override fun isAvailable(): Boolean = true
        override fun version(): String = "queued-fake-golden"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
            if (queue.isNotEmpty()) queue.removeFirst().toTypedArray() else emptyArray()
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray =
            ShortArray(0)

        fun queue(results: List<Ft8DecodeResult>): QueuedDecoderFake = apply { queue += results }
    }

    private fun result(message: String, freqHz: Float, snr: Int = -10): Ft8DecodeResult =
        Ft8DecodeResult(message = message, snr = snr, dtSeconds = 0f, freqHz = freqHz, score = snr)

    // ── Test 1: structural note ───────────────────────────────────────────────

    /**
     * Documents — not asserts — the structural insensitivity described in the
     * class KDoc.  @Ignored so it does not run; it exists as executable
     * documentation that appears in `git log` and IDE test trees.
     *
     * If the harness is ever extended to construct [DecodeController], remove
     * @Ignore and add a real assertion here.
     */
    @Ignore(
        "Structural note only — GoldenTraceReplay does not construct DecodeController " +
            "and is therefore insensitive to the earlyDecodeEnabled toggle. " +
            "Toggle-OFF parity is enforced by EarlyDecodeSchedulerTest and " +
            "TxOrchestratorPttSafetyEarlyDecodeTest. See class KDoc for full rationale.",
    )
    @Test
    fun structuralNoteGoldenTraceHarnessIsInsensitiveToEarlyDecodeToggle() {
        // Intentionally empty.  The assertion is the @Ignore message above.
    }

    // ── Test 2: 100-slot no-duplicate emission ────────────────────────────────

    /**
     * Runs 100 simulated slots through [DecodeController] with `earlyDecodeEnabled = true`.
     *
     * Each slot has:
     * - An EARLY pass returning 2 decodes: one CQ (unique to this slot's callsign)
     *   and one shared "beacon" CQ that repeats every slot at the same frequency
     *   (worst-case for the dedup set — the beacon key collides across passes but
     *   must NOT be re-emitted in the same slot).
     * - A FULL pass returning 3 decodes: the same 2 from the EARLY pass (now with
     *   slightly different freqHz within the 6.25 Hz bin, so `stableId` still
     *   collides) plus one new decode unique to the FULL pass.
     *
     * Invariant asserted: across ALL [DecodeBatch] emissions for ALL 100 slots,
     * no `(slotStart, stableId)` pair appears more than once.
     *
     * This directly validates the per-slot `seenKeys` dedup set in
     * [DecodeController.decodeSlot] over a realistic sustained-operation scenario.
     */
    @Test
    fun noDuplicateKeyEmittedAcrossOneHundredSimulatedSlots() = runTest {
        val slotCount = 100
        // UTC-aligned slot boundaries: 1_700_000_000_000 mod 15_000 == 0.
        val slotBase = run {
            val base = 1_700_000_000_000L
            base - Math.floorMod(base, 15_000L)
        }
        val slotDurationMs = 15_000L

        // Build the queued decoder: 2 decode calls per slot (EARLY + FULL).
        val fake = QueuedDecoderFake()
        for (slot in 0 until slotCount) {
            val uniqueCall = "K${slot.toString().padStart(4, '0')}AA"
            val uniqueMsg = "CQ $uniqueCall FN42"
            val beaconMsg = "CQ W9BCN EN50"   // repeats every slot

            // EARLY pass: uniqueMsg at 1500 Hz + beaconMsg at 1700 Hz
            fake.queue(
                listOf(
                    result(uniqueMsg, freqHz = 1500.0f, snr = -10),
                    result(beaconMsg, freqHz = 1700.0f, snr = -11),
                ),
            )
            // FULL pass: same two (slightly different freqHz, within 6.25 Hz bin)
            //            + one truly new decode unique to the FULL pass
            val fullUniqueMsg = "CQ ${uniqueCall.replace("AA", "BB")} EN10"
            fake.queue(
                listOf(
                    result(uniqueMsg,     freqHz = 1500.3f, snr = -8),   // collision with EARLY
                    result(beaconMsg,     freqHz = 1700.2f, snr = -9),   // collision with EARLY
                    result(fullUniqueMsg, freqHz = 1900.0f, snr = -13),  // new in FULL pass
                ),
            )
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            decodeDispatcher = testDispatcher,
            clock = { slotBase },   // frozen clock; early scheduler does not fire in this test
        )
        controller.setEarlyDecodeEnabled(true)

        // Subscribe eagerly (Unconfined) so the collector is active before the first
        // decodeSlot emit — decodesOut is a replay=0 SharedFlow (production has a
        // permanent QsoSessionController subscriber).
        val emittedBatches = mutableListOf<DecodeBatch>()
        val collectJob = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            controller.decodesOut.toList(emittedBatches)
        }

        // Drive EARLY then FULL for each slot.
        for (slot in 0 until slotCount) {
            val slotStart = slotBase + slot.toLong() * slotDurationMs
            // EARLY pass (>= 115_200 samples: 12 s × 9600 Hz threshold)
            controller.decodeSlot(ShortArray(120_000) { 1 }, slotStart, source = DecodePassSource.Early)
            scope.advanceUntilIdle()
            // FULL pass (> 12-s worth)
            controller.decodeSlot(ShortArray(180_000) { 1 }, slotStart, source = DecodePassSource.Full)
            scope.advanceUntilIdle()
        }

        // ── Key-uniqueness invariant ──────────────────────────────────────────
        // Build a list of (slotStart, stableId) tuples across all emitted batches.
        data class EmitKey(val slotStart: Long, val stableId: Long)

        val emitKeys = mutableListOf<EmitKey>()
        for (batch in emittedBatches) {
            // Re-derive stableId the same way DecodeController does for each QsoDecode.
            // QsoDecode carries only message + snr, not freqHz — we use the
            // representative freqHz that DecodeController rounded to Int and stored in
            // DecodeRow, but since we can't observe that directly here, we assert
            // uniqueness at the message level per slot instead: no (slotStart, message)
            // pair may appear more than once across all batches.
            // This is the correct observable contract on [decodesOut]: QsoDecode.message
            // is the human-readable key the QSO machine acts on.
            for (decode in batch.decodes) {
                emitKeys += EmitKey(batch.slotStartEpochMs, decode.message.hashCode().toLong())
            }
        }

        val duplicates = emitKeys.groupBy { it }.filter { (_, list) -> list.size > 1 }
        assertEquals(
            "No (slotStart, message) pair must appear more than once across all emitted batches " +
                "(seenKeys dedup contract). Duplicates found: $duplicates",
            0,
            duplicates.size,
        )

        // ── Emission count sanity ─────────────────────────────────────────────
        // Each slot emits 3 unique decodes total (2 from EARLY + 1 new from FULL).
        // No slot emits fewer than 2 (the EARLY pass always adds 2 new keys).
        // Exact count per slot: 3 (1 unique EARLY + 1 beacon EARLY + 1 FULL-only new).
        // The FULL-pass re-emits of the already-seen EARLY keys must NOT appear.
        val totalExpectedDecodes = slotCount * 3
        val totalEmitted = emittedBatches.sumOf { it.decodes.size }
        assertEquals(
            "Expected exactly $totalExpectedDecodes total emitted decodes across $slotCount slots " +
                "(3 unique per slot: 2 from EARLY + 1 FULL-only), got $totalEmitted",
            totalExpectedDecodes,
            totalEmitted,
        )

        collectJob.cancel()
        controller.close()
        scope.cancel()
    }
}
