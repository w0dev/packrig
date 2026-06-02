package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QsoMessagesTest {

    @Test
    fun formatsReportsSignedTwoDigits() {
        assertEquals("-05", QsoMessages.formatReport(-5))
        assertEquals("+02", QsoMessages.formatReport(2))
        assertEquals("+13", QsoMessages.formatReport(13))
        assertEquals("-12", QsoMessages.formatReport(-12))
        assertEquals("+00", QsoMessages.formatReport(0))
    }

    @Test
    fun buildsStandardMessages() {
        assertEquals("CQ W0DEV EM26", QsoMessages.cq("W0DEV", "EM26"))
        assertEquals("W0DEV K1ABC FN42", QsoMessages.reply("W0DEV", "K1ABC", "FN42"))
        assertEquals("K1ABC W0DEV -05", QsoMessages.report("K1ABC", "W0DEV", -5))
        assertEquals("K1ABC W0DEV R-12", QsoMessages.rReport("K1ABC", "W0DEV", -12))
        assertEquals("K1ABC W0DEV RRR", QsoMessages.rrr("K1ABC", "W0DEV"))
        assertEquals("K1ABC W0DEV RR73", QsoMessages.rr73("K1ABC", "W0DEV"))
        assertEquals("K1ABC W0DEV 73", QsoMessages.bye73("K1ABC", "W0DEV"))
    }

    @Test
    fun parsesCq() {
        val rx = QsoMessages.parse("CQ W0DEV EM26")
        assertTrue(rx is QsoRx.Cq)
        rx as QsoRx.Cq
        assertEquals("W0DEV", rx.call)
        assertEquals("EM26", rx.grid)
    }

    @Test
    fun parsesCqWithModifier() {
        val rx = QsoMessages.parse("CQ POTA W0DEV EM26")
        assertTrue(rx is QsoRx.Cq)
        rx as QsoRx.Cq
        assertEquals("W0DEV", rx.call)
        assertEquals("EM26", rx.grid)
    }

    @Test
    fun parsesGridReply() {
        val rx = QsoMessages.parse("W0DEV K1ABC FN42")
        assertTrue(rx is QsoRx.GridReply)
        rx as QsoRx.GridReply
        assertEquals("W0DEV", rx.target)
        assertEquals("K1ABC", rx.sender)
        assertEquals("FN42", rx.grid)
    }

    @Test
    fun parsesReportAndRReport() {
        val rep = QsoMessages.parse("W0DEV K1ABC -07")
        assertTrue(rep is QsoRx.Report)
        assertEquals(-7, (rep as QsoRx.Report).snr)

        val rRep = QsoMessages.parse("W0DEV K1ABC R+03")
        assertTrue(rRep is QsoRx.RReport)
        assertEquals(3, (rRep as QsoRx.RReport).snr)
    }

    @Test
    fun parsesRogerVariantsAndBye() {
        assertTrue(QsoMessages.parse("W0DEV K1ABC RRR") is QsoRx.Roger)
        assertTrue(QsoMessages.parse("W0DEV K1ABC RR73") is QsoRx.RogerBye)
        assertTrue(QsoMessages.parse("W0DEV K1ABC 73") is QsoRx.Bye)
    }

    @Test
    fun unknownIsOther() {
        assertEquals(QsoRx.Other, QsoMessages.parse("random text here"))
        assertEquals(QsoRx.Other, QsoMessages.parse(""))
    }
}
