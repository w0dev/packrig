package net.packset.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotTimingTest {

    // 1970-01-01T00:00:07Z -> 7s into the minute -> slot 0, 8s until next slot.
    @Test
    fun slotIndexWithinFirstSlot() {
        val t = 7_000L
        assertEquals(0, SlotTiming.slotIndexInMinute(t))
        assertEquals(0L, SlotTiming.slotStart(t))
        assertEquals(15_000L, SlotTiming.nextSlotStart(t))
        assertEquals(8_000L, SlotTiming.millisUntilNextSlot(t))
    }

    // 47s into the minute -> slot 3 (45..60), next boundary at 60s.
    @Test
    fun slotIndexWithinLastSlot() {
        val t = 47_000L
        assertEquals(3, SlotTiming.slotIndexInMinute(t))
        assertEquals(45_000L, SlotTiming.slotStart(t))
        assertEquals(60_000L, SlotTiming.nextSlotStart(t))
        assertEquals(13_000L, SlotTiming.millisUntilNextSlot(t))
    }

    @Test
    fun exactBoundaryIsStartOfNewSlot() {
        val t = 30_000L
        assertEquals(2, SlotTiming.slotIndexInMinute(t))
        assertEquals(30_000L, SlotTiming.slotStart(t))
        assertEquals(45_000L, SlotTiming.nextSlotStart(t))
        assertEquals(15_000L, SlotTiming.millisUntilNextSlot(t))
        assertEquals(15, SlotTiming.secondsUntilNextSlot(t))
        assertEquals(true, SlotTiming.isEvenSlot(t))
    }

    @Test
    fun oddSlotDetected() {
        assertEquals(false, SlotTiming.isEvenSlot(16_000L))
    }
}
