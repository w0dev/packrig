package net.ft8vc.app.controllers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.AppInfo
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the early-decode scheduler wired into [DecodeController]:
 * - fires one EARLY-pass [DecodeController.decodeSlot] per slot at slotStart+12s
 * - skips when sample count < [DecodeController.EARLY_OFFSET_MS]-threshold (115_200)
 * - cancels pending early job when slot boundary arrives (collectLatest semantics)
 * - respects the [DecodeController.setEarlyDecodeEnabled] toggle
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyDecodeSchedulerTest {

    /** A UTC epoch aligned to a 15-second slot boundary: 1_700_000_000_000 % 15_000 == 0. */
    private val slotStart = 1_700_000_000_000L

    /**
     * Fake that counts EARLY vs FULL decode invocations.
     * Distinguishes them by sample count: early snapshots contain fewer than
     * [AppInfo.SAMPLE_RATE_HZ] * 15 samples (= 180_000); a full-slot flush sends exactly 180_000.
     */
    private inner class CountingFake(
        val earlyCount: AtomicInteger = AtomicInteger(0),
        val fullCount: AtomicInteger = AtomicInteger(0),
    ) : Ft8DecoderApi {
        override fun isAvailable() = true
        override fun version() = "counting-fake"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> {
            if (samples.size < AppInfo.SAMPLE_RATE_HZ * 15) {
                earlyCount.incrementAndGet()
            } else {
                fullCount.incrementAndGet()
            }
            return emptyArray()
        }
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray =
            ShortArray(0)
    }

    /** Mutable clock helper; the test advances [now] to simulate time passing. */
    private class AtomicClock(initial: Long) {
        @Volatile var now: Long = initial
    }

    // ── Test 1 ───────────────────────────────────────────────────────────────

    @Test
    fun `early pass fires once per slot at t plus 12s when toggle on and samples sufficient`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val fakeClock = AtomicClock(slotStart)
        val fake = CountingFake()

        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            decodeDispatcher = testDispatcher,
            clock = { fakeClock.now },
        )
        controller.setEarlyDecodeEnabled(true)

        // Feed 120_000 samples (> 115_200 threshold) at slotStart — no boundary crossed.
        controller.onFrames(ShortArray(120_000) { 1.toShort() })

        // Advance fake clock and coroutine scheduler to slotStart + 12_001 ms.
        fakeClock.now = slotStart + 12_001L
        advanceTimeBy(12_001L)
        scope.advanceUntilIdle()

        assertEquals("expected exactly 1 early pass", 1, fake.earlyCount.get())
        assertEquals("no full pass expected yet", 0, fake.fullCount.get())

        controller.close()
        scope.cancel()
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────

    @Test
    fun `early pass is skipped when fewer than 115_200 samples present`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val fakeClock = AtomicClock(slotStart)
        val fake = CountingFake()

        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            decodeDispatcher = testDispatcher,
            clock = { fakeClock.now },
        )
        controller.setEarlyDecodeEnabled(true)

        // Feed only 100_000 samples — below the 115_200 threshold.
        controller.onFrames(ShortArray(100_000) { 1.toShort() })

        // Advance to t+12s — the scheduler wakes, checks snapshot size, skips.
        fakeClock.now = slotStart + 12_001L
        advanceTimeBy(12_001L)
        scope.advanceUntilIdle()

        assertEquals("early pass must be skipped with insufficient samples", 0, fake.earlyCount.get())

        controller.close()
        scope.cancel()
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────

    @Test
    fun `slot boundary cancels still-pending early job`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val fakeClock = AtomicClock(slotStart)
        val fake = CountingFake()

        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            decodeDispatcher = testDispatcher,
            clock = { fakeClock.now },
        )
        controller.setEarlyDecodeEnabled(true)

        // Feed sufficient samples in slot 0.
        controller.onFrames(ShortArray(120_000) { 1.toShort() })

        // Advance to t+11s — scheduler is mid-wait (delay not yet elapsed).
        fakeClock.now = slotStart + 11_000L
        advanceTimeBy(11_000L)
        scope.advanceUntilIdle()

        // Now cross the slot boundary into slot 1 (slotStart + 15_001 ms).
        // This triggers a new currentSlotStart value, which collectLatest
        // cancels the in-progress delay and starts a fresh lambda for slot 1.
        val slot1Start = slotStart + 15_000L
        fakeClock.now = slot1Start + 1L
        // Feed one sample so onFrames updates currentSlotStart to slot1.
        controller.onFrames(ShortArray(1) { 1.toShort() })

        // Drain pending coroutines so collectLatest cancels the slot-0 lambda BEFORE
        // we advance time into the window where it would otherwise fire.
        scope.advanceUntilIdle()

        // Advance to what would have been old slot 0's t+12s (slotStart+12_001).
        // The scheduler's lambda for slot 0 was cancelled; slot 1 lambda hasn't reached
        // its 12s mark yet (only 1 ms into slot 1; needs 11_999 ms more).
        advanceTimeBy(1_001L) // total elapsed = 12_002 ms from slotStart
        scope.advanceUntilIdle()

        assertEquals("cancelled slot-0 early must not fire", 0, fake.earlyCount.get())

        controller.close()
        scope.cancel()
    }

    // ── Test 4 ───────────────────────────────────────────────────────────────

    @Test
    fun `with toggle off no early pass fires`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val fakeClock = AtomicClock(slotStart)
        val fake = CountingFake()

        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            decodeDispatcher = testDispatcher,
            clock = { fakeClock.now },
        )
        // Disable early decode before any frames arrive.
        controller.setEarlyDecodeEnabled(false)

        // Feed sufficient samples.
        controller.onFrames(ShortArray(120_000) { 1.toShort() })

        // Advance to t+12s.
        fakeClock.now = slotStart + 12_001L
        advanceTimeBy(12_001L)
        scope.advanceUntilIdle()

        assertEquals("no early pass when toggle is off", 0, fake.earlyCount.get())

        controller.close()
        scope.cancel()
    }
}
