package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperateTxOptionsTest {

    @Test
    fun idleMenuOnlyFreeText() {
        val entries = OperateTxOptions.menuEntries(
            qsoActive = false,
            myCall = "W0DEV",
            myGrid = "EM26",
            cqModifier = null,
            form = QsoForm(),
        )
        assertEquals(1, entries.size)
        assertEquals(QsoTxStep.Custom, entries[0].step)
        assertNull(entries[0].preview)
    }

    @Test
    fun activeQsoMenuIncludesSequenceAndFreeText() {
        val form = QsoForm(dxCall = "K1ABC", reportSent = -8)
        val entries = OperateTxOptions.menuEntries(
            qsoActive = true,
            myCall = "W0DEV",
            myGrid = "EM26",
            cqModifier = null,
            form = form,
        )
        assertEquals(7, entries.size)
        assertEquals(QsoTxStep.Cq, entries[0].step)
        assertEquals("CQ W0DEV EM26", entries[0].preview)
        assertEquals("K1ABC W0DEV -08", entries[2].preview)
        assertEquals(QsoTxStep.Custom, entries.last().step)
    }
}
