package net.packset.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCollectorTest {

    private val rate = 12_000

    @Test
    fun flushesCompletedSlotOnBoundaryCrossing() {
        val collector = SlotCollector(rate)
        var flushedSamples: ShortArray? = null
        var flushedSlotStart = -1L
        val onSlot: (ShortArray, Long) -> Unit = { s, t -> flushedSamples = s; flushedSlotStart = t }

        // Slot starting at 30_000 ms. Feed ~15s of audio in 1s chunks.
        val oneSecond = ShortArray(rate)
        var now = 30_000L
        repeat(15) {
            collector.add(oneSecond, now, onSlot)
            now += 1000
        }
        // Nothing flushed yet (still inside the slot).
        assertNull(flushedSamples)

        // Cross into the next slot (45_000 ms) -> previous slot flushes.
        collector.add(oneSecond, 45_000L, onSlot)
        assertEquals(30_000L, flushedSlotStart)
        assertTrue("should have captured ~15s", flushedSamples!!.size >= (rate * 14))
    }

    @Test
    fun skipsShortPartialSlot() {
        val collector = SlotCollector(rate)
        var flushed = false
        val onSlot: (ShortArray, Long) -> Unit = { _, _ -> flushed = true }

        // Only 3 seconds captured before the boundary -> below the min threshold.
        collector.add(ShortArray(rate * 3), 42_000L, onSlot)
        collector.add(ShortArray(rate), 45_000L, onSlot)
        assertTrue("partial slot should be skipped", !flushed)
    }
}
