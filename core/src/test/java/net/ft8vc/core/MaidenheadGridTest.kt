package net.ft8vc.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaidenheadGridTest {

    @Test
    fun isValid4_acceptsStandardLocators() {
        assertTrue(MaidenheadGrid.isValid4("FN42"))
        assertTrue(MaidenheadGrid.isValid4("em26"))
    }

    @Test
    fun isValid4_rejectsShortOrInvalid() {
        assertFalse(MaidenheadGrid.isValid4("FN4"))
        assertFalse(MaidenheadGrid.isValid4("FN420"))
    }

    @Test
    fun distanceKm_increasesWithSeparation() {
        val near = MaidenheadGrid.distanceKm("FN31", "FN32")
        val far = MaidenheadGrid.distanceKm("FN31", "EM26")
        assertNotNull(near)
        assertNotNull(far)
        assertTrue(far!! > near!!)
    }
}
