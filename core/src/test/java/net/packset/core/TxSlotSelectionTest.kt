package net.packset.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxSlotSelectionTest {

    @Test
    fun parityForCallingCq_usesPreference() {
        assertEquals(TxSlotParity.EVEN, TxSlotSelection.parityForCallingCq(TxSlotParity.EVEN))
        assertEquals(TxSlotParity.ODD, TxSlotSelection.parityForCallingCq(TxSlotParity.ODD))
    }

    @Test
    fun answerParity_oppositeOfHeardSlot() {
        assertEquals(TxSlotParity.ODD, TxSlotSelection.answerParity(TxSlotParity.EVEN))
        assertEquals(TxSlotParity.EVEN, TxSlotSelection.answerParity(TxSlotParity.ODD))
    }

    @Test
    fun millisUntilNextTxSlot_waitsForMatchingParity() {
        val t = 7_000L
        assertEquals(8_000L, TxSlotSelection.millisUntilNextTxSlot(t, TxSlotParity.ODD))
        assertEquals(23_000L, TxSlotSelection.millisUntilNextTxSlot(t, TxSlotParity.EVEN))
    }

    @Test
    fun isTxSlot_evenAtZeroSeconds() {
        assertTrue(TxSlotSelection.isTxSlot(0L, TxSlotParity.EVEN))
        assertFalse(TxSlotSelection.isTxSlot(0L, TxSlotParity.ODD))
    }
}
