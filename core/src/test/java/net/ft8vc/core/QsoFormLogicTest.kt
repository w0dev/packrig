package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QsoFormLogicTest {

    private val myCall = "W0DEV"
    private val myGrid = "EM26"

    @Test
    fun composeCqAndReport() {
        val cq = QsoForm(txStep = QsoTxStep.Cq)
        assertEquals("CQ W0DEV EM26", QsoFormLogic.compose(myCall, myGrid, null, cq))

        val report = QsoForm(
            txStep = QsoTxStep.Report,
            dxCall = "K1ABC",
            reportSent = -8,
        )
        assertEquals("K1ABC W0DEV -08", QsoFormLogic.compose(myCall, myGrid, null, report))
    }

    @Test
    fun composeCqWithPotaModifier() {
        val form = QsoForm(txStep = QsoTxStep.Cq)
        assertEquals("CQ POTA W0DEV EM26", QsoFormLogic.compose(myCall, myGrid, "POTA", form))
    }

    @Test
    fun effectiveMessagePrefersCustomWhenManual() {
        val form = QsoForm(
            txStep = QsoTxStep.Cq,
            customMessage = "CQ TEST FN31",
            manualControl = true,
        )
        assertEquals("CQ TEST FN31", QsoFormLogic.effectiveMessage(myCall, myGrid, null, form))
    }

    @Test
    fun parseReportAcceptsSignedValues() {
        assertEquals(-8, QsoFormLogic.parseReport("-08"))
        assertEquals(-8, QsoFormLogic.parseReport("R-08"))
        assertNull(QsoFormLogic.parseReport(""))
    }

    @Test
    fun stepFromStateRoundTrip() {
        val m = QsoMachine(myCall, myGrid)
        m.startCq()
        assertEquals(QsoTxStep.Cq, QsoFormLogic.stepFromState(m.state))
        m.onDecodes(listOf(QsoDecode("W0DEV K1ABC FN42", -8)))
        assertEquals(QsoTxStep.Report, QsoFormLogic.stepFromState(m.state))
    }
}
