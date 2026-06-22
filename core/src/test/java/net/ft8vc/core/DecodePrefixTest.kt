package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodePrefixTest {

    @Test
    fun cqGetsCqGlyph() {
        val prefix = DecodePrefix.prefixFor(
            message = "CQ W0DEV EM26",
            isCq = true,
            isToMe = false,
            qsoActive = false,
            qsoDx = null,
        )
        assertEquals(DecodePrefix.CQ, prefix)
    }

    @Test
    fun directedToMeGetsArrow() {
        val prefix = DecodePrefix.prefixFor(
            message = "W0DEV K1ABC FN42",
            isCq = false,
            isToMe = true,
            qsoActive = false,
            qsoDx = null,
        )
        assertEquals(DecodePrefix.TO_ME, prefix)
    }

    @Test
    fun activeQsoPartnerGetsPointer() {
        val prefix = DecodePrefix.prefixFor(
            message = "W0DEV K1ABC -05",
            isCq = false,
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
        )
        assertEquals(DecodePrefix.PARTNER, prefix)
    }

    @Test
    fun unrelatedDecodeHasNoPrefix() {
        val prefix = DecodePrefix.prefixFor(
            message = "K1ABC W1XYZ FN42",
            isCq = false,
            isToMe = false,
            qsoActive = false,
            qsoDx = null,
        )
        assertEquals("", prefix)
    }

    @Test
    fun toMeSuppressedDuringQso_unlessPartner() {
        val prefix = DecodePrefix.prefixFor(
            message = "W0DEV N5XYZ EM10",
            isCq = false,
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
        )
        assertEquals("", prefix)
    }

    @Test
    fun cqPrefixWinsOverToMe() {
        val prefix = DecodePrefix.prefixFor(
            message = "CQ K1ABC FN42",
            isCq = true,
            isToMe = true,
            qsoActive = false,
            qsoDx = null,
        )
        assertEquals(DecodePrefix.CQ, prefix)
    }
}
