package net.packrig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCollectorClockJumpTest {

    private val rate = AppInfo.SAMPLE_RATE_HZ
    // A UTC instant a little past a slot boundary.
    private val slot0 = SlotTiming.slotStart(1_700_000_000_000L)

    @Test fun `clock jump flushes in-progress slot and re-aligns to new grid`() {
        val collector = SlotCollector(rate)
        val flushed = mutableListOf<Long>()

        // Fill ~90% of slot 0 (enough to exceed the 85% min-fraction gate).
        val nearlyFull = ShortArray((rate * AppInfo.SLOT_SECONDS * 0.9f).toInt()) { 1 }
        collector.add(nearlyFull, slot0 + 100L) { _, slotStart -> flushed += slotStart }
        assertTrue("no flush before a boundary is crossed", flushed.isEmpty())

        // Operator applies a correction: the corrected clock jumps into the NEXT slot.
        val jumped = slot0 + SlotTiming.SLOT_MS + 50L
        collector.add(ShortArray(1) { 1 }, jumped) { _, slotStart -> flushed += slotStart }

        // The misaligned in-progress slot 0 flushed exactly once, tagged to slot 0.
        assertEquals(listOf(slot0), flushed)
    }
}
