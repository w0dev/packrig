package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QsoMachineTest {

    private fun decode(vararg messages: String, snr: Int = -10): List<QsoDecode> =
        messages.map { QsoDecode(it, snr) }

    @Test
    fun initiatorRunsFullSequence() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()

        // 1. We call CQ.
        assertEquals(QsoState.CallingCq, m.state)
        assertEquals("CQ W0DEV EM26", m.txMessage())

        // 2. K1ABC answers with grid; we capture them and move to report.
        assertTrue(m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8)))
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC", m.dxCall)
        assertEquals("FN42", m.dxGrid)
        assertEquals(-8, m.reportSent)
        assertEquals("K1ABC W0DEV -08", m.txMessage())

        // 3. They send R+report; we move to RRR.
        assertTrue(m.onDecodes(decode("W0DEV K1ABC R-15")))
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals(-15, m.reportRcvd)
        assertEquals("K1ABC W0DEV RRR", m.txMessage())

        // 4. They send 73; QSO complete.
        assertTrue(m.onDecodes(decode("W0DEV K1ABC 73")))
        assertEquals(QsoState.Complete, m.state)
        assertNull(m.txMessage())
        assertFalse(m.isActive)
    }

    @Test
    fun answererRunsFullSequence() {
        val m = QsoMachine("K1ABC", "FN42")
        // We heard CQ W0DEV EM26 at -8 and tapped to answer.
        m.answerCq("W0DEV", "EM26", snr = -8)

        assertEquals(QsoState.Answering, m.state)
        assertEquals("W0DEV K1ABC FN42", m.txMessage())
        assertEquals(-8, m.reportSent)

        // They send us a report; we reply R+report.
        assertTrue(m.onDecodes(decode("K1ABC W0DEV -03")))
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals(-3, m.reportRcvd)
        assertEquals("W0DEV K1ABC R-08", m.txMessage())

        // They roger; we send 73.
        assertTrue(m.onDecodes(decode("K1ABC W0DEV RRR")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertEquals("W0DEV K1ABC 73", m.txMessage())

        // After we transmit our 73 the QSO is complete.
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
        assertNull(m.txMessage())
    }

    @Test
    fun answererAcceptsRr73AsRoger() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03"))
        assertEquals(QsoState.SendingRReport, m.state)

        assertTrue(m.onDecodes(decode("K1ABC W0DEV RR73")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
    }

    @Test
    fun initiatorAcceptsRr73AsCompletion() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42"))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals(QsoState.SendingRoger, m.state)

        assertTrue(m.onDecodes(decode("W0DEV K1ABC RR73")))
        assertEquals(QsoState.Complete, m.state)
    }

    @Test
    fun ignoresMessagesForOtherStations() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // Grid reply addressed to someone else must not advance us.
        assertFalse(m.onDecodes(decode("N0XYZ K1ABC FN42")))
        assertEquals(QsoState.CallingCq, m.state)
    }

    @Test
    fun ignoresRepliesFromWrongDxOnceLocked() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42"))
        assertEquals("K1ABC", m.dxCall)

        // A different station sending us R-report shouldn't advance the locked QSO.
        assertFalse(m.onDecodes(decode("W0DEV N0XYZ R-10")))
        assertEquals(QsoState.SendingReport, m.state)
    }

    @Test
    fun repeatsSameMessageUntilExpectedReply() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42"))
        val first = m.txMessage()
        // Unrelated decode: we keep sending the same report.
        assertFalse(m.onDecodes(decode("CQ N0XYZ EN50")))
        assertEquals(first, m.txMessage())
    }

    @Test
    fun resetReturnsToIdle() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.reset()
        assertEquals(QsoState.Idle, m.state)
        assertNull(m.txMessage())
        assertNull(m.dxCall)
    }
}
