package net.packset.app.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun `freqs straddling a bin edge produce split stableIds but share a candidate id`() {
        // 1503.1 / 6.25 = 240.496 → rounds to bin 240; 1503.2 / 6.25 = 240.512 → bin 241.
        // Cross-pass jitter this small must not split the dedup key.
        val a = DecodeRowKey.stableId(slotStart, 1503.1, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1503.2, "CQ K1ABC FN42")
        assertNotEquals("straddling freqs round to different bins — the bug this guards", a, b)

        val candA = DecodeRowKey.candidateIds(slotStart, 1503.1, "CQ K1ABC FN42")
        val candB = DecodeRowKey.candidateIds(slotStart, 1503.2, "CQ K1ABC FN42")
        assertTrue(
            "nearest-two-bin candidates must overlap across the bin edge",
            candA.toSet().intersect(candB.toSet()).isNotEmpty(),
        )
    }

    @Test
    fun `first candidate id equals stableId`() {
        val id = DecodeRowKey.stableId(slotStart, 1503.1, "CQ K1ABC FN42")
        val cand = DecodeRowKey.candidateIds(slotStart, 1503.1, "CQ K1ABC FN42")
        assertEquals(id, cand[0])
    }

    @Test
    fun `freqs two or more bins apart share no candidate id`() {
        // 1500.0 → bin 240 (candidates 240/241); 1515.0 → bin 242.4 → 242 (candidates 242/243)
        val candA = DecodeRowKey.candidateIds(slotStart, 1500.0, "CQ K1ABC FN42")
        val candB = DecodeRowKey.candidateIds(slotStart, 1515.0, "CQ K1ABC FN42")
        assertTrue(candA.toSet().intersect(candB.toSet()).isEmpty())
    }

    @Test
    fun `candidate ids differ for different messages at the same freq`() {
        val candA = DecodeRowKey.candidateIds(slotStart, 1503.1, "CQ K1ABC FN42")
        val candB = DecodeRowKey.candidateIds(slotStart, 1503.1, "CQ W2DEF EM12")
        assertTrue(candA.toSet().intersect(candB.toSet()).isEmpty())
    }

    @Test
    fun `message text is trimmed before hashing`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.0, "  CQ K1ABC FN42  ")
        assertEquals(a, b)
    }
}
