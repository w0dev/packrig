package net.ft8vc.app.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DecodeRowKeyTest {
    private val slotStart = 1_700_000_000_000L

    @Test
    fun `same slot freq bin and message produce equal ids across passes`() {
        // 1499.8 Hz and 1500.4 Hz both fall in the 1500 Hz bin (round to 6.25 Hz grid)
        val a = DecodeRowKey.stableId(slotStart, 1499.8, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.4, "CQ K1ABC FN42")
        assertEquals(a, b)
    }

    @Test
    fun `different message produces distinct id`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.0, "CQ W2DEF EM12")
        assertNotEquals(a, b)
    }

    @Test
    fun `different freq bin produces distinct id`() {
        // 1497 and 1506 are in different 6.25 Hz bins (1497.5 and 1506.25 centers)
        val a = DecodeRowKey.stableId(slotStart, 1497.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1506.0, "CQ K1ABC FN42")
        assertNotEquals(a, b)
    }

    @Test
    fun `different slot produces distinct id`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart + 15_000, 1500.0, "CQ K1ABC FN42")
        assertNotEquals(a, b)
    }

    @Test
    fun `message text is trimmed before hashing`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.0, "  CQ K1ABC FN42  ")
        assertEquals(a, b)
    }
}
