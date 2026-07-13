package net.packrig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeDistanceTest {

    @Test
    fun gridFromMessage_cqAndGridReply() {
        assertEquals("EM26", DecodeDistance.gridFromMessage("CQ W0DEV EM26"))
        assertEquals("FN42", DecodeDistance.gridFromMessage("K1ABC W0DEV FN42"))
    }

    @Test
    fun gridFromMessage_reportHasNoGrid() {
        assertNull(DecodeDistance.gridFromMessage("K1ABC W0DEV -05"))
    }

    @Test
    fun kmFromMessage_nullWhenNoGridInMessage() {
        assertNull(DecodeDistance.kmFromMessage("FN42", "K1ABC W0DEV -05"))
    }

    @Test
    fun kmFromMessage_computesForCq() {
        val km = DecodeDistance.kmFromMessage("FN42", "CQ K1ABC EM26")
        assertNotNull(km)
        assertTrue(km!! > 0)
    }

    @Test
    fun label_formatsKmOrDash() {
        assertEquals("1234", DecodeDistance.label(1234))
        assertEquals("   —", DecodeDistance.label(null))
    }
}
