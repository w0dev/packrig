package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.audio.fakes.FakeUsbAudioPlayback
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import net.ft8vc.ft8native.fakes.Ft8DecoderFake
import net.ft8vc.rig.fakes.FakeRigBackend
import net.ft8vc.rig.fakes.PttEdgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Combined-feature integration test: hunt-and-pounce loop end-to-end.
 *
 * ## Scope (option b — scoped integration)
 *
 * The brief allows scoped integration when wiring the full production loop
 * (DecodeController → QsoSessionController QSO loop → TxOrchestrator) would
 * require real UTC slot-boundary timing (15 s per cycle). Instead this test
 * drives the seams manually in the order the production stack would:
 *
 *  1. [DecodeController.decodeSlot] with [DecodePassSource.Early] at
 *     `slotStart + 12 s` emits a [DecodeBatch] on [DecodeController.decodesOut].
 *  2. The batch (one directed-reply CQ targeting our station) is forwarded to
 *     [QsoSessionController.onDecodeBatch], mirroring the `OperateViewModel`
 *     seam (`decodeController.decodesOut.collect { batch → qsoSession.onDecodeBatch(…) }`).
 *  3. QsoSessionController arms an auto-answer reply (qsoActive = true).
 *  4. [TxOrchestrator.transmit] is called with the reply message — the same
 *     call the QSO loop's `transmitFn` would issue at the next slot boundary.
 *     PTT is asserted to key and release within the test.
 *  5. [DecodeController.decodeSlot] with [DecodePassSource.Full] on the SAME
 *     slot verifies the dedup contract: the EARLY-emitted decode is NOT
 *     re-emitted on [decodesOut].
 *
 * ## Station scenario
 *
 * Our station: W2DEF / FN42.
 * DX station (K1ABC) calls CQ. We discover it on the early pass at t+12 s.
 * The directed-reply CQ message ("CQ K1ABC FN42") arms our auto-answer into
 * K1ABC. Our reply ("W2DEF K1ABC FN42") is transmitted via [TxOrchestrator].
 *
 * ## PTT assertion
 *
 * [TxOrchestrator.transmit] is synchronous-ish: it runs `encode` on the
 * encode dispatcher, then calls [RigSession.keyPtt] on the same dispatcher
 * thread before blocking on [FakeUsbAudioPlayback.playBlocking]. The test
 * uses [runBlocking] (matching [TxOrchestratorLateTxTest]) so the PTT edge
 * is observable immediately after `transmit` returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyDecodeLateTxIntegrationTest {

    companion object {
        /** W2DEF is our station call. K1ABC is the DX. FN42 is K1ABC's grid. */
        private const val MY_CALL = "W2DEF"
        private const val MY_GRID = "EM26"
        private const val DX_CALL = "K1ABC"
        private const val DX_GRID = "FN42"

        /** UTC-aligned slot start (1_700_000_000_000 mod 15_000 == 0). */
        private val SLOT_START: Long = run {
            val base = 1_700_000_000_000L
            base - Math.floorMod(base, 15_000L)
        }

        /** The CQ from K1ABC that appears on the EARLY pass. */
        private const val DX_CQ_MESSAGE = "CQ $DX_CALL $DX_GRID"

        /** Our auto-answer reply to K1ABC (grid exchange). */
        private const val OUR_REPLY_MESSAGE = "$MY_CALL $DX_CALL $MY_GRID"

        private const val TX_FREQ_HZ = 1500

        /** Short slot for TxOrchestrator watchdog safety (mirrors TxOrchestratorLateTxTest). */
        private const val SHORT_SLOT_MS = 60L
        private const val WATCHDOG_GRACE_MS_TEST = 30L
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * A [Ft8DecoderApi] that returns a queued list of [Ft8DecodeResult] per
     * [decode] call, and a default PCM buffer from [encode]. This is a local
     * variant of [QueuedFake] from [DecodeControllerEarlyDedupTest], kept
     * local so this test file does not depend on another test's inner class.
     */
    private class QueuedDecoderFake(
        private val queue: ArrayDeque<List<Ft8DecodeResult>> = ArrayDeque(),
    ) : Ft8DecoderApi {
        override fun isAvailable(): Boolean = true
        override fun version(): String = "queued-fake"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
            if (queue.isNotEmpty()) queue.removeFirst().toTypedArray() else emptyArray()
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray =
            ShortArray(sampleRate * 15)

        fun queue(results: List<Ft8DecodeResult>) {
            queue += results
        }
    }

    /** Build a [TxOrchestrator] using real [Dispatchers.Default] + injected fakes. */
    private fun buildTxOrchestrator(
        decoder: Ft8DecoderApi,
        playback: FakeUsbAudioPlayback,
        rig: FakeRigBackend,
        scope: CoroutineScope,
    ): TxOrchestrator {
        val txExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tx-e2e-test").also { it.isDaemon = true }
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
            lateStartTxEnabledProvider = { true },
        )
    }

    // ── Test 1: EARLY pass discovers CQ, arms auto-answer, PTT keys ───────────

    /**
     * Hunt-and-pounce loop:
     *
     *  1. EARLY pass at slotStart+12 s finds K1ABC's CQ.
     *  2. [DecodeController.decodesOut] emits a batch with passSource=Early.
     *  3. [QsoSessionController.onDecodeBatch] (autoAnswerCq=true) arms reply.
     *  4. [TxOrchestrator.transmit] is invoked → PTT keys and releases.
     *
     * This is the "happy path" of the combined early-decode + late-TX feature:
     * a CQ spotted 3 s early arms the QSO machine so the response is ready at
     * the next slot boundary.
     */
    @Test
    fun `early CQ arms auto-answer and PTT keys when transmit is invoked`() = runTest {
        // ── 1. DecodeController (TestScope / StandardTestDispatcher) ──────────
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val decodeScope = TestScope(testDispatcher)
        val clockMs = SLOT_START   // clock frozen at slot start

        val queuedDecoder = QueuedDecoderFake()
        // Queue the early result: K1ABC's CQ at SNR=-10, 1500 Hz.
        queuedDecoder.queue(
            listOf(Ft8DecodeResult(DX_CQ_MESSAGE, snr = -10, dtSeconds = 0.1f, freqHz = 1500f, score = 40)),
        )

        val decodeController = DecodeController(
            decoder = queuedDecoder,
            scope = decodeScope,
            decodeDispatcher = testDispatcher,
            clock = { clockMs },
        )
        decodeController.setStationContext(MY_CALL, MY_GRID)
        decodeController.setEarlyDecodeEnabled(true)

        // Collect all DecodeBatch emissions from this slot.
        val emittedBatches = mutableListOf<DecodeBatch>()
        val collectJob = decodeScope.launch {
            decodeController.decodesOut.toList(emittedBatches)
        }

        // ── 2. QsoSessionController (UnconfinedTestDispatcher via existing pattern) ──
        val transmittedMessages = Collections.synchronizedList(mutableListOf<String>())
        val txScope = CoroutineScope(testDispatcher)
        val qsoExecutor = Executors.newSingleThreadExecutor()
        val qsoController = QsoSessionController(
            scope = txScope,
            transmitFn = { message -> transmittedMessages += message; true },
            onQsoComplete = { },
            notifyFn = { _, _ -> },
            resumeCaptureIfNeeded = { },
            executor = qsoExecutor,
            qsoDispatcher = testDispatcher,
            clock = { clockMs },
            slotClockIntervalMs = 10_000L,
        )
        qsoController.updateStationProfile(MY_CALL, MY_GRID, potaMode = false, potaPark = "")
        qsoController.setTxEnabled(true)
        qsoController.setOperating(true)
        qsoController.setAutoSeqEnabled(true)
        qsoController.setAutoAnswerCqEnabled(true)
        qsoController.setAnswerPolicy(net.ft8vc.core.AnswerPolicy.FIRST)

        // ── 3. Fire the EARLY decode pass (slotStart + 12 000 ms worth of samples) ──
        // 115_200 samples = 12 s × 9600 Hz is the minimum for early pass.
        // We use 120_000 to exceed the threshold.
        decodeController.decodeSlot(
            samples = ShortArray(120_000) { 100.toShort() },
            slotStartEpochMs = SLOT_START,
            source = DecodePassSource.Early,
        )
        decodeScope.advanceUntilIdle()

        // ── 4. Assert: EARLY batch emitted with the CQ ────────────────────────
        assertEquals("EARLY pass must emit exactly 1 batch", 1, emittedBatches.size)
        val earlyBatch = emittedBatches.first()
        assertEquals(
            "EARLY batch must contain exactly 1 decode (the CQ)",
            1,
            earlyBatch.decodes.size,
        )
        assertEquals(
            "Emitted decode must be K1ABC's CQ",
            DX_CQ_MESSAGE,
            earlyBatch.decodes.first().message,
        )

        // ── 5. Forward EARLY batch to QsoSessionController (mirrors OperateViewModel seam) ──
        qsoController.onDecodeBatch(earlyBatch.decodes, earlyBatch.slotParity)
        decodeScope.advanceUntilIdle()

        // ── 6. Assert: QSO machine is armed (auto-answered the CQ) ────────────
        val qsoSlice = qsoController.slice.value
        assertTrue(
            "QsoSessionController must be active after auto-answering K1ABC's CQ",
            qsoSlice.qsoActive,
        )
        assertEquals(
            "Target DX must be K1ABC",
            DX_CALL,
            qsoSlice.qsoDx,
        )

        // ── 7. Transmit via TxOrchestrator (mirrors QSO loop's transmitFn) ────
        val rig = FakeRigBackend()
        val playback = FakeUsbAudioPlayback()
        // Use real Dispatchers.Default for TxOrchestrator (matches TxOrchestratorLateTxTest).
        val txOrchestratorScope = CoroutineScope(Dispatchers.Default + Job())
        val txOrchestrator = buildTxOrchestrator(
            decoder = Ft8DecoderFake(),   // encode-only; no decode needed here
            playback = playback,
            rig = rig,
            scope = txOrchestratorScope,
        )

        val result = runBlocking {
            txOrchestrator.transmit(OUR_REPLY_MESSAGE, TX_FREQ_HZ)
        }

        // ── 8. Assert: PTT keyed and released (4-layer safety intact) ─────────
        assertTrue("transmit must succeed", result)

        val edges = rig.pttEdgesSnapshot()
        assertEquals("PTT keyed exactly once", 1, edges.count { it.kind == PttEdgeKind.KEY })
        assertEquals("PTT released exactly once", 1, edges.count { it.kind == PttEdgeKind.RELEASE })
        assertFalse("PTT must not remain keyed after transmit", rig.pttKeyed)

        // ── Cleanup ───────────────────────────────────────────────────────────
        collectJob.cancel()
        decodeController.close()
        qsoController.close()
        txOrchestrator.close()
        txOrchestratorScope.cancel()
        txScope.cancel()
        decodeScope.cancel()
    }

    // ── Test 2: FULL pass does NOT re-emit the EARLY-discovered decode ────────

    /**
     * Dedup contract end-to-end: after an EARLY pass emits K1ABC's CQ on
     * [DecodeController.decodesOut], a subsequent FULL pass on the same slot
     * must NOT re-emit that same message (it may update the SNR in the UI
     * list, but must not emit a second [DecodeBatch] containing it).
     *
     * This exercises the per-slot [seenKeys] dedup set in [DecodeController]
     * end-to-end through the [decodesOut] SharedFlow that [QsoSessionController]
     * consumes.
     */
    @Test
    fun `full pass does not re-emit decode already emitted by early pass`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val decodeScope = TestScope(testDispatcher)

        val queuedDecoder = QueuedDecoderFake()
        // EARLY pass: K1ABC's CQ.
        queuedDecoder.queue(
            listOf(Ft8DecodeResult(DX_CQ_MESSAGE, snr = -10, dtSeconds = 0.1f, freqHz = 1500f, score = 40)),
        )
        // FULL pass: same K1ABC CQ (higher SNR), plus a new callsign W9XYZ.
        // K1ABC must NOT be re-emitted; W9XYZ IS new and must be emitted.
        queuedDecoder.queue(
            listOf(
                Ft8DecodeResult(DX_CQ_MESSAGE, snr = -7, dtSeconds = 0.0f, freqHz = 1500.3f, score = 43),
                Ft8DecodeResult("CQ W9XYZ EN50", snr = -12, dtSeconds = 0.2f, freqHz = 1700f, score = 38),
            ),
        )

        val decodeController = DecodeController(
            decoder = queuedDecoder,
            scope = decodeScope,
            decodeDispatcher = testDispatcher,
            clock = { SLOT_START },
        )
        decodeController.setStationContext(MY_CALL, MY_GRID)

        val emittedBatches = mutableListOf<DecodeBatch>()
        val collectJob = decodeScope.launch {
            decodeController.decodesOut.toList(emittedBatches)
        }

        // EARLY pass
        decodeController.decodeSlot(
            samples = ShortArray(120_000) { 100.toShort() },
            slotStartEpochMs = SLOT_START,
            source = DecodePassSource.Early,
        )
        decodeScope.advanceUntilIdle()

        // FULL pass
        decodeController.decodeSlot(
            samples = ShortArray(180_000) { 100.toShort() },
            slotStartEpochMs = SLOT_START,
            source = DecodePassSource.Full,
        )
        decodeScope.advanceUntilIdle()

        // ── Assertions ────────────────────────────────────────────────────────

        // All messages ever emitted across both batches.
        val allEmittedMessages = emittedBatches.flatMap { batch ->
            batch.decodes.map { it.message }
        }

        // K1ABC's CQ was emitted exactly once (EARLY pass); FULL pass must not re-emit it.
        val dxCqCount = allEmittedMessages.count { it == DX_CQ_MESSAGE }
        assertEquals(
            "K1ABC's CQ must be emitted exactly once across EARLY + FULL passes (dedup contract)",
            1,
            dxCqCount,
        )

        // W9XYZ is new in the FULL pass and must be emitted.
        val w9xyzCount = allEmittedMessages.count { it == "CQ W9XYZ EN50" }
        assertEquals(
            "W9XYZ must be emitted exactly once (new decode from FULL pass)",
            1,
            w9xyzCount,
        )

        // Total unique messages = total emitted (no duplicates at all).
        assertEquals(
            "No message must appear more than once across all emitted batches",
            allEmittedMessages.size,
            allEmittedMessages.distinct().size,
        )

        // UI slice must show 2 rows (K1ABC + W9XYZ), with K1ABC's SNR updated by the FULL pass.
        val slice = decodeController.slice.value
        assertEquals("UI slice must contain 2 unique decode rows", 2, slice.decodes.size)

        val dxRow = slice.decodes.firstOrNull { it.message == DX_CQ_MESSAGE }
        assertEquals(
            "K1ABC's row SNR must be updated to FULL-pass value (-7)",
            -7,
            dxRow?.snr,
        )

        collectJob.cancel()
        decodeController.close()
        decodeScope.cancel()
    }
}
