package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxSlotSelectionTest {

    @Test
    fun parityForCallingCq_usesPreference() {
        assertEquals(0, TxSlotSelection.parityForCallingCq(TxSlotParity.EVEN))
        assertEquals(1, TxSlotSelection.parityForCallingCq(TxSlotParity.ODD))
    }

    @Test
    fun answerParity_oppositeOfHeardSlot() {
        assertEquals(1, TxSlotSelection.answerParity(0))
        assertEquals(0, TxSlotSelection.answerParity(1))
    }

    @Test
    fun millisUntilNextTxSlot_waitsForMatchingParity() {
        val t = 7_000L
        assertEquals(8_000L, TxSlotSelection.millisUntilNextTxSlot(t, TxSlotParity.ODD.bit))
        assertEquals(23_000L, TxSlotSelection.millisUntilNextTxSlot(t, TxSlotParity.EVEN.bit))
    }

    @Test
    fun isTxSlot_evenAtZeroSeconds() {
        assertTrue(TxSlotSelection.isTxSlot(0L, TxSlotParity.EVEN.bit))
        assertFalse(TxSlotSelection.isTxSlot(0L, TxSlotParity.ODD.bit))
    }
}
