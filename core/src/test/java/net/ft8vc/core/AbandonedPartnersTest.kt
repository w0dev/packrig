package net.ft8vc.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonedPartnersTest {

    @Test
    fun abandonBlocksBaseCallsign() {
        val blocked = AbandonedPartners()
        blocked.abandon("K1ABC/P")
        assertTrue(blocked.isAbandoned("K1ABC"))
        assertTrue(blocked.isAbandoned("K1ABC/P"))
        assertFalse(blocked.isAbandoned("N0XYZ"))
    }

    @Test
    fun allowResumeClearsBlock() {
        val blocked = AbandonedPartners()
        blocked.abandon("K1ABC")
        blocked.allowResume("K1ABC")
        assertFalse(blocked.isAbandoned("K1ABC"))
    }

    @Test
    fun clearRemovesAll() {
        val blocked = AbandonedPartners()
        blocked.abandon("K1ABC")
        blocked.abandon("N0XYZ")
        blocked.clear()
        assertFalse(blocked.isAbandoned("K1ABC"))
    }
}
