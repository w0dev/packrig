package net.packset.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        // They send us a report (their transmission measured at -8 on our side);
        // we reply R+report carrying that measurement.
        assertTrue(m.onDecodes(decode("K1ABC W0DEV -03", snr = -8)))
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
    fun answererRefreshesReportSentFromReportDecodeSnr() {
        val m = QsoMachine("K1ABC", "FN42")
        // Their CQ decoded at +16; conditions changed by the time they reply.
        m.answerCq("W0DEV", "EM26", snr = 16)
        assertEquals(16, m.reportSent)

        // Their report arrives; we measure that transmission at +8. The R-report
        // must carry the fresh measurement, not the stale CQ-time SNR (WSJT-X behavior).
        assertTrue(m.onDecodes(listOf(QsoDecode("K1ABC W0DEV +16", snr = 8))))
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals(16, m.reportRcvd)
        assertEquals(8, m.reportSent)
        assertEquals("W0DEV K1ABC R+08", m.txMessage())
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

    @Test
    fun snapshotAvailableOnComplete() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42"))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        m.onDecodes(decode("W0DEV K1ABC 73"))
        assertEquals(QsoState.Complete, m.state)
        val snap = m.snapshot(1_700_000_000_000L)
        requireNotNull(snap)
        assertEquals("W0DEV", snap.myCall)
        assertEquals("K1ABC", snap.dxCall)
        assertEquals("FN42", snap.dxGrid)
        assertEquals(-15, snap.reportRcvd)
        assertEquals(1_700_000_000_000L, snap.completedAtEpochMs)
    }

    @Test
    fun callingCqUsesModifier() {
        val m = QsoMachine("W0DEV", "EM26", "POTA")
        m.startCq()
        assertEquals("CQ POTA W0DEV EM26", m.txMessage())
    }

    @Test
    fun callingCqPileupUsesBestSnrPolicy() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        assertTrue(
            m.onDecodes(
                listOf(
                    QsoDecode("W0DEV K1ABC FN42", -10),
                    QsoDecode("W0DEV N0XYZ FN20", -4),
                ),
                AnswerPolicy.BEST_SNR,
            ),
        )
        assertEquals("N0XYZ", m.dxCall)
    }

    @Test
    fun recordTransmittedCountsUnansweredCycles() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.recordTransmitted()
        m.recordTransmitted()
        assertEquals(2, m.unansweredTxCycles)
        assertFalse(m.noReplyLimitExceeded(5))
    }

    @Test
    fun onDecodesProgressResetsUnansweredCounter() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.recordTransmitted()
        m.recordTransmitted()
        m.onDecodes(listOf(QsoDecode("W0DEV K1ABC FN42", -8)))
        assertEquals(0, m.unansweredTxCycles)
    }

    @Test
    fun noReplyLimitExceededTriggersAbandonThreshold() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        repeat(5) { m.recordTransmitted() }
        assertTrue(m.noReplyLimitExceeded(5))
        assertFalse(m.noReplyLimitExceeded(0))
    }

    @Test
    fun sendingSeventyThreeCompletesWithoutExtraUnansweredCount() {
        val m = QsoMachine("K1ABC", "FN42")
        m.resumeAnswererAfterRoger("W0DEV")
        m.recordTransmitted()
        assertEquals(QsoState.Complete, m.state)
        assertEquals(0, m.unansweredTxCycles)
    }

    @Test
    fun callingCqSkipsAbandonedGridReply() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        assertFalse(
            m.onDecodes(
                listOf(QsoDecode("W0DEV K1ABC FN42", -8)),
                excludedDx = setOf("K1ABC"),
            ),
        )
        assertEquals(QsoState.CallingCq, m.state)
    }

    @Test
    fun manualControlBlocksAutoAdvance() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.setManualControl(true)
        assertFalse(m.onDecodes(decode("W0DEV K1ABC FN42")))
        assertEquals(QsoState.CallingCq, m.state)
    }

    @Test
    fun customMessageOverridesComposedTx() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.setCustomMessage("CQ TEST FN31")
        assertEquals("CQ TEST FN31", m.txMessage())
    }

    @Test
    fun customMessageClearsAfterRecordTransmitted() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.setCustomMessage("CQ TEST FN31")
        m.recordTransmitted()
        assertEquals("CQ W0DEV EM26", m.txMessage())
    }

    @Test
    fun applyFormSetsMidSequenceState() {
        val m = QsoMachine("W0DEV", "EM26")
        m.applyForm(
            QsoForm(
                dxCall = "K1ABC",
                reportSent = -8,
                txStep = QsoTxStep.Report,
                manualControl = true,
            ),
        )
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC W0DEV -08", m.txMessage())
    }

    @Test
    fun fromDxMatchesCompoundAndHashedForms() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // Caller addresses our portable form; we still capture them.
        assertTrue(m.onDecodes(decode("W0DEV/P K1ABC FN42", snr = -8)))
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC", m.dxCall)
        // Their R-report arrives under a hashed compound form of the same call.
        assertTrue(m.onDecodes(decode("W0DEV <PJ4/K1ABC> R-15")))
        assertEquals(QsoState.SendingRoger, m.state)
    }

    @Test
    fun rr73ModeSendsRr73AndCompletesOnTransmit() {
        val m = QsoMachine("W0DEV", "EM26", initiatorRr73 = true)
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals("K1ABC W0DEV RR73", m.txMessage())
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
        assertNull(m.txMessage())
        assertFalse(m.isActive)
    }

    @Test
    fun rrrModeUnchangedWhenFlagOff() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals("K1ABC W0DEV RRR", m.txMessage())
        m.markTransmitted()
        assertEquals(QsoState.SendingRoger, m.state) // still waiting for their 73
    }

    @Test
    fun rr73ModeStillAcceptsEarly73BeforeOurTransmit() {
        val m = QsoMachine("W0DEV", "EM26", initiatorRr73 = true)
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertTrue(m.onDecodes(decode("W0DEV K1ABC 73")))
        assertEquals(QsoState.Complete, m.state)
    }

    @Test
    fun cqAcceptsDirectReportReply() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // Tx1-skip hunter: payload report -10; we measured them at -5.
        assertTrue(m.onDecodes(decode("W0DEV K3XYZ -10", snr = -5)))
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals("K3XYZ", m.dxCall)
        assertEquals(-10, m.reportRcvd)
        assertEquals(-5, m.reportSent)
        assertEquals("K3XYZ W0DEV R-05", m.txMessage())
        // They roger; we send 73; complete on transmit.
        assertTrue(m.onDecodes(decode("W0DEV K3XYZ RR73")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
    }

    @Test
    fun cqAcceptsRReportTailRepair() {
        val m = QsoMachine("W0DEV", "EM26", initiatorRr73 = true)
        m.startCq()
        // Partner lost our RR73 and retried their R-report while we resumed CQ.
        assertTrue(m.onDecodes(decode("W0DEV K1ABC R-08", snr = -6)))
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals("K1ABC W0DEV RR73", m.txMessage())
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
    }

    @Test
    fun cqIgnoresStrayRogerBye() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        assertFalse(m.onDecodes(decode("W0DEV K1ABC RR73")))
        assertEquals(QsoState.CallingCq, m.state)
    }

    @Test
    fun dxAnsweredAnotherStation_trueWhenDxReportsToThirdParty() {
        val m = QsoMachine("W0DEV", "EM26")
        m.answerCq("K1ABC", "FN42", snr = -8)
        assertTrue(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC +03")))
        // The query must not mutate the machine.
        assertEquals(QsoState.Answering, m.state)
        assertEquals("K1ABC", m.dxCall)
    }

    @Test
    fun dxAnsweredAnotherStation_falseWhenReportIsToUs() {
        val m = QsoMachine("W0DEV", "EM26")
        m.answerCq("K1ABC", "FN42", snr = -8)
        assertFalse(m.dxAnsweredAnotherStation(decode("W0DEV K1ABC +03")))
        // The normal advance path still applies.
        assertTrue(m.onDecodes(decode("W0DEV K1ABC +03")))
        assertEquals(QsoState.SendingRReport, m.state)
    }

    @Test
    fun dxAnsweredAnotherStation_falseForOtherSenders() {
        val m = QsoMachine("W0DEV", "EM26")
        m.answerCq("K1ABC", "FN42", snr = -8)
        // A report between two unrelated stations is not our DX moving on.
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K9AAA +03")))
    }

    @Test
    fun dxAnsweredAnotherStation_falseForNonReportTraffic() {
        val m = QsoMachine("W0DEV", "EM26")
        m.answerCq("K1ABC", "FN42", snr = -8)
        // RRR/RR73/73 to a third party is often the tail of the DX's PREVIOUS
        // QSO (partner re-sent R-report); the DX is still available — keep calling.
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC RRR")))
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC RR73")))
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC 73")))
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC FN42")))
    }

    @Test
    fun dxAnsweredAnotherStation_falseOutsideAnsweringState() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC +03")))

        // Once the DX has reported to US we are the chosen partner; ignore
        // third-party traffic from them.
        val m2 = QsoMachine("W0DEV", "EM26")
        m2.answerCq("K1ABC", "FN42", snr = -8)
        assertTrue(m2.onDecodes(decode("W0DEV K1ABC +03")))
        assertEquals(QsoState.SendingRReport, m2.state)
        assertFalse(m2.dxAnsweredAnotherStation(decode("N0XYZ K1ABC +03")))
    }

    @Test
    fun dxAnsweredAnotherStation_falseUnderManualControl() {
        val m = QsoMachine("W0DEV", "EM26")
        m.answerCq("K1ABC", "FN42", snr = -8)
        m.setManualControl(true)
        assertFalse(m.dxAnsweredAnotherStation(decode("N0XYZ K1ABC +03")))
    }

    @Test
    fun cqPrefersActionableReplyOverStrayRoger() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // FIRST policy: the stray RR73 comes first in decode order but is not
        // an accepted kind, so the grid reply must win.
        assertTrue(
            m.onDecodes(
                listOf(
                    QsoDecode("W0DEV K9AAA RR73", -3),
                    QsoDecode("W0DEV K1ABC FN42", -8),
                ),
            ),
        )
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC", m.dxCall)
    }

    // ── WSJT-X log timing: answerer is loggable at RRR/RR73 receipt ────

    @Test
    fun answererLoggableAtRogerReceipt() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        assertEquals(QsoState.SendingRReport, m.state)
        // Not confirmed yet — nothing to log before their roger arrives.
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))

        assertTrue(m.onDecodes(decode("K1ABC W0DEV RRR")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertTrue(m.confirmedByPartner)
        val snap = m.snapshot(1000L)
        assertNotNull(snap)
        assertEquals("W0DEV", snap!!.dxCall)
        assertEquals(-8, snap.reportSent)
        assertEquals(-3, snap.reportRcvd)
        assertEquals(QsoRole.Answerer, snap.role)
        assertEquals(1000L, snap.completedAtEpochMs)
    }

    @Test
    fun answererLoggableAtRr73Receipt() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))

        assertTrue(m.onDecodes(decode("K1ABC W0DEV RR73")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertTrue(m.confirmedByPartner)
        assertNotNull(m.snapshot(1000L))
        // The courtesy 73 is still the next TX.
        assertEquals("W0DEV K1ABC 73", m.txMessage())
    }

    @Test
    fun confirmedSnapshotSurvivesMarkTransmitted() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        m.onDecodes(decode("K1ABC W0DEV RR73"))
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
        assertNotNull(m.snapshot(1000L))
    }

    @Test
    fun resumeAnswererAfterRoger_isNotConfirmed_logsOnlyAtComplete() {
        // Tapping a stray RRR/RR73 decode to resume: the machine has no report
        // data, so it must keep the v1.0 behavior — loggable only at Complete.
        val m = QsoMachine("K1ABC", "FN42")
        m.resumeAnswererAfterRoger("W0DEV")
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
        m.markTransmitted()
        assertNotNull(m.snapshot(1000L))
    }

    @Test
    fun initiatorNotLoggableBeforeComplete() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42"))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals(QsoState.SendingRoger, m.state)
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
    }

    @Test
    fun manualControl_neverConfirms_logsOnlyAtComplete() {
        // Under manual control onDecodes must not advance, so the partner's
        // RR73 cannot early-confirm — the user owns the flow; log at Complete.
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        m.setManualControl(true)
        assertFalse(m.onDecodes(decode("K1ABC W0DEV RR73")))
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
    }

    @Test
    fun resetClearsConfirmedByPartner() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        m.onDecodes(decode("K1ABC W0DEV RR73"))
        assertTrue(m.confirmedByPartner)
        m.reset()
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
    }
}
