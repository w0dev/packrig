package net.packset.app.ui.operate

import net.packset.core.TxSlotParity
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotTintTest {

    @Test
    fun evenSlotIsTinted() {
        assertEquals(SLOT_TINT_ALPHA, slotTintAlpha(TxSlotParity.EVEN), 0f)
    }

    @Test
    fun oddSlotIsNotTinted() {
        assertEquals(0f, slotTintAlpha(TxSlotParity.ODD), 0f)
    }

    @Test
    fun tintAlphaIsSubtleAndValid() {
        // Neutral, subtle overlay: in-range and low enough not to compete
        // with the semantic category fills (FILL_ALPHA = 0.16f).
        assertEquals(true, SLOT_TINT_ALPHA in 0.04f..0.10f)
    }
}
