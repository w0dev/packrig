package net.packset.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockOffsetEstimatorTest {

    @Test
    fun nullUntilMinimumSamples() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(0.68f, 0.68f, 0.68f))
        assertNull(e.offsetSeconds)
        e.onSlotDts(listOf(0.68f))
        assertEquals(0.0f, e.offsetSeconds!!, 0.001f)
    }

    @Test
    fun fastClockGivesPositiveOffset() {
        val e = ClockOffsetEstimator()
        // Phone clock fast → signals land late in our buffer → DT above nominal.
        e.onSlotDts(listOf(1.9f, 2.0f, 1.9f, 2.0f))
        assertEquals(1.27f, e.offsetSeconds!!, 0.001f)
    }

    @Test
    fun medianIgnoresOneOutlier() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(0.68f, 0.68f, 0.68f, 0.68f, 9.9f))
        assertEquals(0.0f, e.offsetSeconds!!, 0.001f)
    }

    @Test
    fun windowRollsAndQuietSlotsExpireTheEstimate() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(2.0f, 2.0f, 2.0f, 2.0f))
        assertEquals(1.32f, e.offsetSeconds!!, 0.001f)
        // Four quiet slots push the data out of the 4-slot window.
        repeat(4) { e.onSlotDts(emptyList()) }
        assertNull(e.offsetSeconds)
    }

    @Test
    fun resetClearsEverything() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(2.0f, 2.0f, 2.0f, 2.0f))
        e.reset()
        assertNull(e.offsetSeconds)
    }
}
