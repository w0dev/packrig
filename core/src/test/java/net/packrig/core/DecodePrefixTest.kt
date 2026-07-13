package net.packrig.core

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
    fun toMeKeepsArrowDuringQso_tailEnder() {
        // Spec 2026-07-04: MY_CALL never turns off mid-QSO. A station other
        // than the partner calling me during a QSO keeps the → glyph.
        val prefix = DecodePrefix.prefixFor(
            message = "W0DEV N5XYZ EM10",
            isCq = false,
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
        )
        assertEquals(DecodePrefix.TO_ME, prefix)
    }

    @Test
    fun partnerGlyphRequiresWholeTokenMatch_prefixCollision() {
        // qsoDx "K1AB" must not claim a row mentioning "K1ABC"; the to-me row
        // keeps the → glyph instead of the partner ▸.
        val prefix = DecodePrefix.prefixFor(
            message = "W0DEV K1ABC -05",
            isCq = false,
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1AB",
        )
        assertEquals(DecodePrefix.TO_ME, prefix)
    }

    @Test
    fun glyphForMapsEveryCategory() {
        assertEquals(DecodePrefix.PARTNER, DecodePrefix.glyphFor(DecodeCategory.PARTNER))
        assertEquals(DecodePrefix.TO_ME, DecodePrefix.glyphFor(DecodeCategory.MY_CALL))
        assertEquals(DecodePrefix.CQ, DecodePrefix.glyphFor(DecodeCategory.CQ_NEW))
        assertEquals(DecodePrefix.CQ, DecodePrefix.glyphFor(DecodeCategory.CQ_WORKED_OTHER_BAND))
        assertEquals(DecodePrefix.CQ, DecodePrefix.glyphFor(DecodeCategory.CQ_WORKED_THIS_BAND))
        assertEquals("", DecodePrefix.glyphFor(DecodeCategory.OWN_TX))
        assertEquals("", DecodePrefix.glyphFor(DecodeCategory.OTHER))
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
