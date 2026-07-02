package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QsoResumeTest {

    @Test
    fun findsGridReplyToMe() {
        val opp = QsoResume.findOpportunity(
            "W0DEV",
            "EM26",
            listOf(QsoDecode("W0DEV K1ABC FN42", -8)),
        )
        requireNotNull(opp)
        assertEquals(QsoResume.Kind.InitiatorGridReply, opp.kind)
        assertEquals("K1ABC", opp.dxCall)
        assertEquals("FN42", opp.dxGrid)
        assertEquals(-8, opp.snr)
    }

    @Test
    fun ignoresGridReplyToOtherStation() {
        assertNull(QsoResume.findOpportunity("W0DEV", "EM26", listOf(QsoDecode("N0XYZ K1ABC FN42", -8))))
    }

    @Test
    fun resumeInitiatorAfterGridReplyTxMessage() {
        val m = QsoMachine("W0DEV", "EM26")
        QsoResume.apply(
            m,
            QsoResume.Opportunity("K1ABC", "FN42", QsoResume.Kind.InitiatorGridReply, -8),
        )
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC W0DEV -08", m.txMessage())
    }

    @Test
    fun resumeAnswererAfterReport() {
        val m = QsoMachine("K1ABC", "FN42")
        QsoResume.apply(
            m,
            QsoResume.Opportunity("W0DEV", null, QsoResume.Kind.AnswererReport, -3),
        )
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals("W0DEV K1ABC R-03", m.txMessage())
    }

    @Test
    fun isDirectedToMe() {
        assertTrue(QsoResume.isDirectedToMe("W0DEV", "W0DEV K1ABC FN42"))
        assertFalse(QsoResume.isDirectedToMe("W0DEV", "CQ W0DEV EM26"))
    }

    @Test
    fun opportunityCarriesPayloadReportDistinctFromMeasuredSnr() {
        // Payload says -10 (their report of us); we measured them at -3.
        val opp = QsoResume.opportunityFromDecode("W0DEV", QsoDecode("W0DEV K1ABC -10", -3))!!
        assertEquals(QsoResume.Kind.AnswererReport, opp.kind)
        assertEquals(-10, opp.payloadReport)
        assertEquals(-3, opp.snr)
    }

    @Test
    fun applySetsDistinctReportFieldsForAnswererReport() {
        val m = QsoMachine("W0DEV", "EM26")
        val opp = QsoResume.opportunityFromDecode("W0DEV", QsoDecode("W0DEV K1ABC -10", -3))!!
        QsoResume.apply(m, opp)
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals(-10, m.reportRcvd)   // what they sent us
        assertEquals(-3, m.reportSent)    // what we measured and will send
        assertEquals("K1ABC W0DEV R-03", m.txMessage())
    }

    @Test
    fun applySetsDistinctReportFieldsForInitiatorRReport() {
        val m = QsoMachine("W0DEV", "EM26")
        val opp = QsoResume.opportunityFromDecode("W0DEV", QsoDecode("W0DEV K1ABC R-08", -4))!!
        QsoResume.apply(m, opp)
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals(-8, m.reportRcvd)
        assertEquals(-4, m.reportSent)
    }
}
