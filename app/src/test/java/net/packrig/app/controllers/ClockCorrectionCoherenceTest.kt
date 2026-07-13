package net.packrig.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.packrig.core.ClockCorrection
import net.packrig.core.SlotTiming
import net.packrig.core.TxSlotParity
import net.packrig.core.TxSlotSelection
import net.packrig.ft8native.Ft8DecodeResult
import net.packrig.ft8native.fakes.Ft8DecoderFake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression test for the DT self-cal core invariant: RX ([DecodeController])
 * and TX ([net.packrig.app.controllers.TxOrchestrator] /
 * [net.packrig.app.controllers.QsoSessionController]) share ONE
 * [ClockCorrection] instance as their `clock: () -> Long` seam, so an applied
 * residual shifts the RX slot grid and the TX slot-parity value together.
 *
 * This is test-only: it locks existing (correct) wiring. It must not require
 * any production change to pass — if it does, the wiring regressed and the
 * fix belongs on the controller, not here.
 *
 * The TX side is asserted via the shared corrected clock fed through the real
 * [TxSlotSelection.slotParity] formula (the exact expression the TX seams use
 * to gate keying) rather than by driving a live transmit loop, which is
 * timing-heavy and flake-prone in a coroutine test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockCorrectionCoherenceTest {

    private val rawClockMs = AtomicLong(SLOT_0_START_EPOCH_MS)
    private val clockCorrection = ClockCorrection(rawClock = { rawClockMs.get() })

    private lateinit var decoder: Ft8DecoderFake
    private lateinit var controllerScope: CoroutineScope
    private lateinit var controller: DecodeController

    private val utcTimeFormat = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Before fun setUp() {
        rawClockMs.set(SLOT_0_START_EPOCH_MS)
        clockCorrection.reset()
        decoder = Ft8DecoderFake()
        controllerScope = CoroutineScope(UnconfinedTestDispatcher())
        // UnconfinedTestDispatcher so scope.launch(decodeDispatcher) runs inline —
        // assertions immediately after onFrames see the decoded slice without races.
        val executor = Executors.newSingleThreadExecutor()
        controller = DecodeController(
            decoder = decoder,
            scope = controllerScope,
            executor = executor,
            decodeDispatcher = UnconfinedTestDispatcher(),
            clock = clockCorrection::now,
        )
        controller.setStationContext("W0DEV", "EM26")
    }

    @After fun tearDown() {
        controller.close()
        controllerScope.cancel()
    }

    /** Drive the SlotCollector across a UTC slot boundary so the queued canned decode flushes. */
    private fun flushOneSlot() {
        // Fill enough samples (>=85% of a slot) at the current corrected clock.
        controller.onFrames(ShortArray(180_000) { 1000 })
        // Advance the RAW clock past the boundary; the next add() trips SlotCollector.flush.
        rawClockMs.set(rawClockMs.get() + 15_001L)
        controller.onFrames(ShortArray(1))
    }

    @Test
    fun rxSlotTag_derivesFrom_correctedClock_notRawClock() = runTest {
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50)),
        )

        // No correction applied yet: corrected clock == raw clock, so this alone
        // wouldn't distinguish "reads clockCorrection.now()" from "reads raw
        // clock directly." The distinguishing assertion is in the next test;
        // this one locks the baseline expectation the second test builds on.
        // Capture the expected slot BEFORE flushOneSlot advances the raw clock
        // past the boundary -- the row tags the slot that just ENDED, not the
        // slot the clock is in once the flush-triggering advance has happened.
        val expectedSlotStart = SlotTiming.slotStart(clockCorrection.now())
        flushOneSlot()

        val expectedTimeUtc = utcTimeFormat.format(Date(expectedSlotStart))
        val row = controller.slice.value.decodes.single()
        assertEquals(expectedTimeUtc, row.timeUtc)
    }

    @Test
    fun applyResidualSeconds_shiftsRxSlotTag_andTxParity_fromTheSameCorrectedClock() = runTest {
        // Slot 0 (SLOT_0_START_EPOCH_MS) is slot-index 2 in its minute -> EVEN parity.
        val parityBeforeApply = TxSlotSelection.slotParity(SlotTiming.slotStart(clockCorrection.now()))
        assertEquals(TxSlotParity.EVEN, parityBeforeApply)

        // Move the corrected clock forward one whole slot (+15s) by applying a
        // NEGATIVE residual: now() = rawClock() - offsetMs, so a -15s residual
        // sets offsetMs = -15000 and now() = raw + 15000 -- landing in the very
        // next (opposite-parity) slot without touching the raw clock at all.
        clockCorrection.applyResidualSeconds(-15.0f)

        val shiftedSlotStart = SlotTiming.slotStart(clockCorrection.now())
        val shiftedParity = TxSlotSelection.slotParity(shiftedSlotStart)
        // TX side of the invariant: parity flipped, computed purely from the
        // shared corrected clock via the exact formula TxOrchestrator /
        // QsoSessionController use to gate keying.
        assertEquals(TxSlotParity.ODD, shiftedParity)

        // RX side of the invariant: a flush after the apply tags its row with
        // the SAME shifted slot start -- i.e. DecodeController's clock seam
        // (clockCorrection::now) is reading the corrected, not raw, clock.
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K2DEF EM73", -10, 0.0f, 1200f, 40)),
        )
        flushOneSlot()

        val expectedTimeUtc = utcTimeFormat.format(Date(shiftedSlotStart))
        val row = controller.slice.value.decodes.single()
        assertEquals(expectedTimeUtc, row.timeUtc)
    }

    private companion object {
        // 1700000010000 % 60000 -> slot index 2 in its minute (EVEN parity).
        const val SLOT_0_START_EPOCH_MS = 1_700_000_010_000L
    }
}
