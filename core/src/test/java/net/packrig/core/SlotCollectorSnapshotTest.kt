package net.packrig.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotCollectorSnapshotTest {
    private val sampleRate = AppInfo.SAMPLE_RATE_HZ
    private val slotStartMs = 0L  // any slot start

    @Test
    fun `snapshot returns null before any samples`() {
        val collector = SlotCollector(sampleRate)
        assertNull(collector.snapshot())
    }

    @Test
    fun `snapshot returns defensive copy of in-progress buffer`() {
        val collector = SlotCollector(sampleRate)
        val frames = ShortArray(12_000) { (it and 0xFF).toShort() }  // 1s of samples
        collector.add(frames, slotStartMs) { _, _ -> }

        val snap = collector.snapshot()
        assertEquals(12_000, snap?.size)
        assertArrayEquals(frames, snap)
    }

    @Test
    fun `mutating returned snapshot does not affect subsequent onSlot output`() {
        // Use minSlotFraction = 0f so the boundary flush fires regardless of fill level —
        // this test is about the snapshot defensive-copy contract, not the min-fill gate.
        val collector = SlotCollector(sampleRate, minSlotFraction = 0f)
        val frames = ShortArray(12_000) { (it and 0xFF).toShort() }
        collector.add(frames, slotStartMs) { _, _ -> }

        val snap = collector.snapshot()!!
        snap.fill(0xFFFF.toShort())  // mutate caller copy

        // Advance to next slot, capture the flushed buffer
        var flushed: ShortArray? = null
        collector.add(ShortArray(0), slotStartMs + 15_000) { samples, _ -> flushed = samples }

        // Original samples should be intact, not the mutated values
        assertEquals(12_000, flushed?.size)
        assertArrayEquals(frames, flushed)
    }
}
