package net.packrig.app.controllers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.packrig.core.AppInfo
import net.packrig.ft8native.Ft8DecodeResult
import net.packrig.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the early-decode scheduler wired into [DecodeController]:
 * - fires one EARLY-pass [DecodeController.decodeSlot] per slot at slotStart+12s
 * - skips when the in-progress buffer holds < 115_200 samples (80% of 12 s)
 * - respects the [DecodeController.setEarlyDecodeEnabled] toggle, including a
 *   flip that happens WHILE the early-pass delay is pending (post-delay re-check)
 *
 * ## Time model
 *
 * The scheduler computes `waitMs = (slotStart + EARLY_OFFSET_MS) - clock()` and
 * then `delay(waitMs)`. For the virtual clock and the virtual delay to share one
 * timeline, the injected [DecodeController.clock] is derived from the test
 * scheduler's own virtual time: `clock = { slotStart + scope.testScheduler.currentTime }`.
 * That makes `advanceTimeBy`/`advanceUntilIdle` the single source of truth — the
 * early pass fires at virtual time 12_000, exactly as it would on a wall clock.
 *
 * The controller's scheduler is an infinite `collectLatest`, so the controller is
 * built on a dedicated [TestScope] that is explicitly cancelled at the end of each
 * test (the controller's own `close()` only shuts the decode executor, not the
 * launched collector).
 *
 * ## A note on slot-boundary cancellation
 *
 * `collectLatest` cancels a still-pending early-pass lambda when `currentSlotStart`
 * advances. Under normal timing the early pass (t+12 s) always completes ~3 s
 * BEFORE the next slot boundary (t+15 s), so a boundary never preempts a pending
 * early pass — the cancellation is a defensive measure. Its observable contract,
 * "exactly one early schedule is armed per slot," is covered by the single-slot
 * fire test below plus the per-slot re-arm that the production `onFrames` drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarlyDecodeSchedulerTest {

    /** A UTC epoch aligned to a 15-second slot boundary: 1_700_000_000_000 % 15_000 == 0. */
    private val slotStart = 1_700_000_000_000L

    /** 12 kHz × ~12 s buffer worth of samples; comfortably above the 115_200 floor. */
    private val sufficientSamples = ShortArray(120_000) { 1.toShort() }

    /**
     * Fake that counts EARLY vs FULL decode invocations by sample count: an early
     * snapshot here carries [sufficientSamples] (120_000) which is below the
     * full-slot flush size ([AppInfo.SAMPLE_RATE_HZ] × 15 = 180_000). These tests
     * never cross a slot boundary, so no full-slot flush occurs and every decode
     * call is an early pass.
     */
    private class CountingFake(
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

    /** Build a controller whose clock is the scope's virtual time, offset from [slotStart]. */
    private fun controllerOn(scope: TestScope, fake: CountingFake): DecodeController =
        DecodeController(
            decoder = fake,
            scope = scope,
            decodeDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { slotStart + scope.testScheduler.currentTime },
        )

    // ── Test 1 ───────────────────────────────────────────────────────────────

    @Test
    fun `early pass fires once per slot at t plus 12s when toggle on and samples sufficient`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val fake = CountingFake()
        val controller = controllerOn(scope, fake)
        controller.setEarlyDecodeEnabled(true)

        // Feed > 115_200 samples at slotStart — no boundary crossed.
        controller.onFrames(sufficientSamples)

        // The early-pass delay fires at virtual time 12_000.
        scope.advanceUntilIdle()

        assertEquals("expected exactly 1 early pass", 1, fake.earlyCount.get())
        assertEquals("no full pass expected (no boundary crossed)", 0, fake.fullCount.get())

        controller.close()
        scope.cancel()
    }

    // ── Test 2 ───────────────────────────────────────────────────────────────

    @Test
    fun `early pass is skipped when fewer than 115_200 samples present`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val fake = CountingFake()
        val controller = controllerOn(scope, fake)
        controller.setEarlyDecodeEnabled(true)

        // Feed only 100_000 samples — below the 115_200 threshold.
        controller.onFrames(ShortArray(100_000) { 1.toShort() })

        scope.advanceUntilIdle()

        assertEquals("early pass must be skipped with insufficient samples", 0, fake.earlyCount.get())

        controller.close()
        scope.cancel()
    }

    // ── Test 3 ───────────────────────────────────────────────────────────────

    @Test
    fun `toggle flipped off during the pending delay suppresses the early pass`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val fake = CountingFake()
        val controller = controllerOn(scope, fake)
        controller.setEarlyDecodeEnabled(true)

        // Slot 0: schedules the early pass to fire at virtual time 12_000.
        controller.onFrames(sufficientSamples)

        // Advance to mid-delay (virtual 6_000) — the early pass has NOT fired yet.
        scope.advanceTimeBy(6_000L)
        scope.runCurrent()
        assertEquals("early pass must not have fired before t+12s", 0, fake.earlyCount.get())

        // Flip the toggle OFF while the delay is still pending.
        controller.setEarlyDecodeEnabled(false)

        // Advance past the would-be fire moment (virtual 12_000).
        scope.advanceUntilIdle()

        assertEquals(
            "post-delay re-check must suppress the early pass after the toggle flips off",
            0,
            fake.earlyCount.get(),
        )

        controller.close()
        scope.cancel()
    }

    // ── Test 4 ───────────────────────────────────────────────────────────────

    @Test
    fun `with toggle off from the start no early pass fires`() = runTest {
        val scope = TestScope(StandardTestDispatcher())
        val fake = CountingFake()
        val controller = controllerOn(scope, fake)
        // Disable early decode before any frames arrive (pre-delay guard).
        controller.setEarlyDecodeEnabled(false)

        controller.onFrames(sufficientSamples)

        scope.advanceUntilIdle()

        assertEquals("no early pass when toggle is off", 0, fake.earlyCount.get())

        controller.close()
        scope.cancel()
    }
}
