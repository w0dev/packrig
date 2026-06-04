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
        assertNull(QsoResume.findOpportunity("W0DEV", listOf(QsoDecode("N0XYZ K1ABC FN42", -8))))
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
}
