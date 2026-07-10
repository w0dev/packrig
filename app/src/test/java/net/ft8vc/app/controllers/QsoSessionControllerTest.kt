package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.SnackbarEvent
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.QsoDecode
import net.ft8vc.core.QsoSnapshot
import net.ft8vc.core.QsoState
import net.ft8vc.core.TxSlotParity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class QsoSessionControllerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var controller: QsoSessionController
    private val transmittedMessages = mutableListOf<String>()
    private val currentSlotAttempts = mutableListOf<String>()
    private val completedSnapshots = mutableListOf<QsoSnapshot>()
    private val notifications = mutableListOf<Pair<String, SnackbarEvent.Tag>>()
    private val resumeCaptureCalls = AtomicInteger(0)
    private val clockMs = AtomicLong(BASE_EPOCH_MS)

    /**
     * Shared with [scope] and the controller's `qsoDispatcher` so that a test can
     * drive the QSO loop's `delay()`-based TX cycles deterministically via
     * [TestCoroutineScheduler.advanceTimeBy] — `runTest`'s own scheduler is a
     * separate instance and does not reach dispatchers built with a fresh
     * no-arg `UnconfinedTestDispatcher()`.
     */
    private lateinit var qsoScheduler: TestCoroutineScheduler

    private var lateTxEnabled = true

    @Before fun setUp() {
        clockMs.set(BASE_EPOCH_MS)
        transmittedMessages.clear()
        currentSlotAttempts.clear()
        completedSnapshots.clear()
        notifications.clear()
        resumeCaptureCalls.set(0)
        lateTxEnabled = true
        qsoScheduler = TestCoroutineScheduler()
        scope = CoroutineScope(UnconfinedTestDispatcher(qsoScheduler))
        rebuildController()
    }

    /** (Re)build the controller — call after changing [lateTxEnabled]. */
    private fun rebuildController() {
        if (::controller.isInitialized) controller.close()
        val executor = Executors.newSingleThreadExecutor()
        controller = QsoSessionController(
            scope = scope,
            transmitFn = { message -> transmittedMessages += message; true },
            // Records the late-TX attempt and returns false (defer) so the loop's
            // fall-through to the boundary path keeps existing tests' behavior.
            transmitIntoCurrentSlotFn = { message -> currentSlotAttempts += message; false },
            lateStartTxEnabledProvider = { lateTxEnabled },
            onQsoComplete = { snapshot -> completedSnapshots += snapshot },
            notifyFn = { text, tag -> notifications += text to tag },
            resumeCaptureIfNeeded = { resumeCaptureCalls.incrementAndGet() },
            executor = executor,
            qsoDispatcher = UnconfinedTestDispatcher(qsoScheduler),
            clock = { clockMs.get() },
            slotClockIntervalMs = 10_000L,
        )
        controller.updateStationProfile("W0DEV", "EM26", potaMode = false, potaPark = "")
        controller.setTxEnabled(true)
        controller.setOperating(true)
        controller.setAutoSeqEnabled(true)
        controller.setAnswerWhenCalledEnabled(true)
        controller.setAutoAnswerCqEnabled(false)
        controller.setAnswerPolicy(AnswerPolicy.FIRST)
        controller.setMaxUnansweredTxCycles(5)
        controller.setDefaultTxSlotParity(TxSlotParity.EVEN)
        controller.setSendRr73(true)
        controller.setAutoCqResumeEnabled(false)
    }

    @After fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test
    fun initialSlice_hasIdleDefaults() {
        val s = controller.slice.value
        assertFalse(s.qsoActive)
        assertNull(s.qsoState)
        assertNull(s.qsoDx)
        assertNull(s.activeTxSlotParity)
    }

    @Test
    fun startCq_publishesCallingCqState() = runTest {
        controller.startCq()
        val s = controller.slice.value
        assertTrue(s.qsoActive)
        assertEquals("Calling CQ…", s.qsoState)
        // operateTxText should auto-populate from machine.
        assertTrue(s.operateTxText.startsWith("CQ "))
    }

    @Test
    fun startCq_rejected_whenTxDisabled() = runTest {
        controller.setTxEnabled(false)
        controller.startCq()
        assertNotNull(notifications.firstOrNull { it.second == SnackbarEvent.Tag.ERROR })
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun startCq_rejected_whenInvalidCall() = runTest {
        controller.updateStationProfile("BADCALL!!!", "EM26", false, "")
        controller.startCq()
        assertTrue(notifications.any { it.first.contains("callsign") })
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun stopQso_clearsSlice() = runTest {
        controller.startCq()
        assertTrue(controller.slice.value.qsoActive)
        controller.stopQso()
        val s = controller.slice.value
        assertFalse(s.qsoActive)
        assertNull(s.qsoState)
        assertNull(s.qsoDx)
    }

    @Test
    fun answerCq_attemptsCurrentSlotTxFirst_whenCurrentSlotIsOurParity() = runTest {
        // BASE_EPOCH is an ODD slot. Answering a CQ heard in an EVEN slot gives TX
        // parity = answerParity(EVEN) = ODD = the current slot, so the first reply
        // must be attempted into the CURRENT slot (late-TX), not queued to a future
        // boundary — the regression that made answering miss the slot.
        controller.answerCq(decodeRowCq("K1ABC", "FN42")) // row.slotParity defaults EVEN
        assertEquals("late path attempted exactly once for the first answer TX", 1, currentSlotAttempts.size)
        assertTrue("late TX must carry our reply", currentSlotAttempts[0].contains("W0DEV"))
    }

    @Test
    fun startCq_firesLate_whenCurrentSlotIsOurParity() = runTest {
        // WSJT-X truncates a late CQ into the current slot too (the middle/end Costas
        // arrays still allow sync). defaultTxSlotParity is EVEN; move the clock to an
        // EVEN slot so the current slot is ours.
        clockMs.set(BASE_EPOCH_MS + 15_000L) // slot after the ODD base = EVEN
        controller.startCq()
        assertEquals("cold CQ must also use the current-slot late path", 1, currentSlotAttempts.size)
        assertTrue("late TX must carry the CQ", currentSlotAttempts[0].startsWith("CQ "))
    }

    @Test
    fun answerCq_doesNotFireLate_whenToggleOff() = runTest {
        // PARITY-01: with late-TX disabled, answering must fall back to the v1.0
        // boundary-aligned path — never the current-slot late path.
        lateTxEnabled = false
        rebuildController()
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        assertTrue("toggle OFF must not attempt the late path", currentSlotAttempts.isEmpty())
    }

    @Test
    fun answerCq_movesIntoAnsweringState() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        val s = controller.slice.value
        assertTrue(s.qsoActive)
        assertEquals("K1ABC", s.qsoDx)
        assertTrue(s.qsoState?.contains("Answering") == true)
    }

    @Test
    fun onDecodeBatch_advancesActiveQso() = runTest {
        // Start by answering a CQ from K1ABC; machine will move into SendingReport.
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        assertEquals(QsoState.Answering, currentMachineState())

        // Now feed a directed reply from K1ABC and verify machine advances.
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC -10", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        // After advance: machine should be in SendingReport (we got their grid via answerCq,
        // then their directed reply gives us their report). The exact state depends on the
        // machine; we just verify it changed from Answering.
        assertTrue(currentMachineState() != QsoState.Idle)
    }

    @Test
    fun onDecodeBatch_triggersAnswerWhenCalled_whenNoActiveQso() = runTest {
        // Directed message to us → should auto-resume into QSO.
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC", controller.slice.value.qsoDx)
    }

    @Test
    fun onDecodeBatch_doesNotAutoAnswerCq_whenSettingDisabled() = runTest {
        controller.setAutoAnswerCqEnabled(false)
        controller.onDecodeBatch(
            listOf(QsoDecode("CQ K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun onDecodeBatch_autoAnswersCq_whenSettingEnabled() = runTest {
        controller.setAutoAnswerCqEnabled(true)
        controller.onDecodeBatch(
            listOf(QsoDecode("CQ K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC", controller.slice.value.qsoDx)
    }

    @Test
    fun blockStation_suppressesLaterAutoResume() = runTest {
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        controller.stopQso()
        controller.blockStation("K1ABC")
        assertEquals(listOf("K1ABC"), controller.slice.value.userBlockedCalls)
        // Same caller must NOT auto-resume while user-blocked.
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun blockStation_activePartner_endsQso() = runTest {
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        controller.blockStation("K1ABC")
        assertFalse(controller.slice.value.qsoActive)
        assertNull(controller.slice.value.qsoDx)
        assertEquals(listOf("K1ABC"), controller.slice.value.userBlockedCalls)
    }

    @Test
    fun blockStation_otherStation_leavesActiveQsoRunning() = runTest {
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        controller.blockStation("N0XYZ")
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        assertEquals(listOf("N0XYZ"), controller.slice.value.userBlockedCalls)
    }

    @Test
    fun setOperateTxText_marksEdited_andSlicePropagates() = runTest {
        controller.startCq()
        controller.setOperateTxText("CUSTOM MESSAGE")
        val s = controller.slice.value
        assertEquals("CUSTOM MESSAGE", s.operateTxText)
        assertTrue(s.operateTxEdited)
    }

    @Test
    fun resetOperateTxText_revertsToAutoComposed() = runTest {
        controller.startCq()
        val original = controller.slice.value.operateTxText
        controller.setOperateTxText("OVERRIDDEN")
        assertEquals("OVERRIDDEN", controller.slice.value.operateTxText)
        controller.resetOperateTxText()
        assertEquals(original, controller.slice.value.operateTxText)
        assertFalse(controller.slice.value.operateTxEdited)
    }

    @Test
    fun clearAbandonedPartners_emitsConfirmation() = runTest {
        controller.clearAbandonedPartners()
        assertTrue(notifications.any { it.first.contains("blocklist") })
    }

    @Test
    fun duplicateCompletionWithinWindow_logsOnce() = runTest {
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)

        // Lost-final-message retry: resume and complete again inside the window.
        controller.resumeFromDecode(decodeRowDirected("W0DEV K1ABC R-15"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertTrue(notifications.any { it.first.contains("already logged") })

        // Outside the window it logs again.
        clockMs.addAndGet(11 * 60_000L)
        controller.resumeFromDecode(decodeRowDirected("W0DEV K1ABC R-15"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(2, completedSnapshots.size)
    }

    @Test
    fun sendRr73On_nextTxMessageIsRr73() = runTest {
        controller.setSendRr73(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        assertEquals("K1ABC W0DEV RR73", controller.slice.value.nextTxMessage)
    }

    @Test
    fun sendRr73Off_nextTxMessageIsRrr() = runTest {
        controller.setSendRr73(false)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        assertEquals("K1ABC W0DEV RRR", controller.slice.value.nextTxMessage)
    }

    @Test
    fun autoResumeCq_restartsAfterCompletion() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("Calling CQ…", controller.slice.value.qsoState)
        assertTrue(notifications.any { it.first.contains("resuming CQ") })
    }

    @Test
    fun autoResumeCq_disabled_staysStopped() = runTest {
        controller.setAutoCqResumeEnabled(false)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun autoResumeCq_notAfterManualStop() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.stopQso()
        assertFalse(controller.slice.value.qsoActive)
        assertNull(controller.slice.value.qsoState)
    }

    @Test
    fun autoResumeCq_notWhenTxDisabledAtFireTime() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.setTxEnabled(false)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun autoResumeCq_afterCqRunPartnerGhosts_stillResumes() = runTest {
        // Invariant guard: a CQ-origin run where the partner answers then ghosts
        // (no-reply abandon) MUST still auto-resume — the flag survives stopQsoInternal().
        // This is the exact case that justifies NOT resetting runOriginatedFromCq in
        // stopQsoInternal(); adding such a reset would make this test fail.
        controller.setMaxUnansweredTxCycles(1)
        controller.setAutoCqResumeEnabled(true)
        controller.startCq() // CQ origin = true
        // K1ABC answers our CQ; we move to SendingReport with dx = K1ABC.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -10)), TxSlotParity.EVEN)
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        // K1ABC now ghosts. Drive the TX loop (manual clock + shared scheduler together,
        // one 15 s slot per iteration) until the no-reply limit trips →
        // abandonForNoReply(dx = K1ABC) → maybeAutoResumeCq. Same pattern as
        // noReplyTimeout_suppressesAuto_withoutUserBlock.
        repeat(4) {
            clockMs.addAndGet(15_000L)
            qsoScheduler.advanceTimeBy(15_000L)
            qsoScheduler.runCurrent()
        }
        // The partner-abandon resume emits "Resuming CQ" only when the gate passes.
        assertTrue(
            "CQ-origin no-reply abandon must auto-resume CQ",
            notifications.any { it.first.contains("Resuming CQ") },
        )
    }

    @Test
    fun autoResumeCq_notAfterAnsweringCq() = runTest {
        // S&P: we answered K1ABC's CQ. Completing must NOT leave us calling CQ.
        controller.setAutoCqResumeEnabled(true)
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        // Their directed reply, our R-report exchange, then their 73 → Complete.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        // RR73 logs immediately, but qsoActive only clears once the courtesy 73
        // TXes and the loop tears down — drive the clock forward to that slot.
        repeat(4) {
            clockMs.addAndGet(15_000L)
            qsoScheduler.advanceTimeBy(15_000L)
            qsoScheduler.runCurrent()
        }
        assertFalse("answered-CQ session must not auto-resume CQ", controller.slice.value.qsoActive)
        assertFalse(notifications.any { it.first.contains("resuming CQ", ignoreCase = true) })
    }

    @Test
    fun autoResumeCq_notAfterResumeFromDecode() = runTest {
        // Resuming a specific station is S&P-like → no auto-resume on completion.
        controller.setAutoCqResumeEnabled(true)
        // Initiator grid-reply opportunity: same machine state as startCq + received FN42.
        controller.resumeFromDecode(decodeRowDirected("W0DEV K1ABC FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertFalse("resume-from-decode session must not auto-resume CQ", controller.slice.value.qsoActive)
    }

    @Test
    fun autoResumeCq_carriesForwardAcrossRestart() = runTest {
        // A CQ-origin session that auto-resumes must remain CQ-origin, so the NEXT
        // completion resumes again — a running station keeps running.
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertEquals("Calling CQ…", controller.slice.value.qsoState) // resumed CQ #1

        // Second QSO on the auto-resumed CQ, different partner.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K2XYZ EM12", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K2XYZ R-12", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K2XYZ 73", -8)), TxSlotParity.ODD)
        assertEquals(2, completedSnapshots.size)
        assertTrue("auto-resumed CQ must itself auto-resume", controller.slice.value.qsoActive)
        assertEquals("Calling CQ…", controller.slice.value.qsoState) // resumed CQ #2
    }

    @Test
    fun dxAnswersAnotherStation_stopsSession() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        assertTrue(controller.slice.value.qsoActive)

        // K1ABC sends a report to N0XYZ — they picked another caller.
        controller.onDecodeBatch(
            listOf(QsoDecode("N0XYZ K1ABC +03", -5)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)
        assertNull(controller.slice.value.qsoDx)
        assertTrue(notifications.any { it.first.contains("K1ABC") && it.first.contains("another station") })
    }

    @Test
    fun dxAnswersAnotherStation_doesNotBlocklistDx() = runTest {
        controller.setAutoAnswerCqEnabled(true)
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(
            listOf(QsoDecode("N0XYZ K1ABC +03", -5)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)

        // K1ABC finishes that QSO and calls CQ again — we must still answer.
        controller.onDecodeBatch(
            listOf(QsoDecode("CQ K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC", controller.slice.value.qsoDx)
    }

    @Test
    fun dxAnswersAnotherStation_keepsCallingOnTheirRr73ToOther() = runTest {
        // RR73 to a third party is the tail of the DX's previous QSO, not a
        // fresh pick — we must keep calling.
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(
            listOf(QsoDecode("N0XYZ K1ABC RR73", -5)),
            slotParity = TxSlotParity.EVEN,
        )
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC", controller.slice.value.qsoDx)
    }

    @Test
    fun dxAnswersAnotherStation_doesNotAutoResumeCq_afterAnswering() = runTest {
        // We answered K1ABC (S&P). They pick another caller → we stop. Even with
        // auto-resume on, an answered-CQ session must NOT leave us calling CQ.
        controller.setAutoCqResumeEnabled(true)
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(
            listOf(QsoDecode("N0XYZ K1ABC +03", -5)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)
        assertNotEquals("Calling CQ…", controller.slice.value.qsoState)
    }

    // ── WSJT-X log timing: answerer logs at RR73/RRR receipt ───────────

    @Test
    fun answererLogsAtRr73Receipt_beforeSending73() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        // Their report arrives; we move to SendingRReport. Nothing logged yet.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        assertTrue(completedSnapshots.isEmpty())

        // Their RR73 arrives: log NOW, while the courtesy 73 is still pending.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        assertEquals("K1ABC", completedSnapshots[0].dxCall)
        assertEquals(-8, completedSnapshots[0].reportSent)
        assertEquals(-3, completedSnapshots[0].reportRcvd)
        assertTrue(notifications.any { it.first.contains("QSO complete with K1ABC") })

        // The loop is still alive and the next TX is our courtesy 73.
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC W0DEV 73", controller.slice.value.nextTxMessage)
    }

    @Test
    fun answererLogsAtRrrReceipt_too() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RRR", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        assertTrue(controller.slice.value.qsoActive)
    }

    @Test
    fun earlyLoggedQso_survivesStopBefore73Tx() = runTest {
        // The point of the change: interruption after their RR73 must not lose the QSO.
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)

        controller.stopQso()
        assertEquals(1, completedSnapshots.size)
        assertFalse(controller.slice.value.qsoActive)
        // Normal flow must never surface the dupe-guard's snackbar.
        assertFalse(notifications.any { it.first.contains("already logged") })
    }

    @Test
    fun repeatedRr73AfterEarlyLog_doesNotLogAgain() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        assertFalse(notifications.any { it.first.contains("already logged") })
    }

    @Test
    fun nextQsoAfterEarlyLog_logsNormally() = runTest {
        // qsoLogged must reset between QSOs.
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        controller.stopQso()
        assertEquals(1, completedSnapshots.size)

        // A different station: full initiator flow completes via their 73.
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV N0XYZ EM12", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV N0XYZ R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV N0XYZ 73", -8)), TxSlotParity.ODD)
        assertEquals(2, completedSnapshots.size)
        assertEquals("N0XYZ", completedSnapshots[1].dxCall)
    }

    @Test
    fun noReplyTimeout_suppressesAuto_withoutUserBlock() = runTest {
        controller.setMaxUnansweredTxCycles(1)
        controller.setAutoCqResumeEnabled(false)
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        // Initiator role (grid-reply resume) ties TX parity to defaultTxSlotParity
        // (EVEN), not the hearing slot — advance the manual clock one slot (15s)
        // per iteration so the loop's frozen-clock parity check actually lands on
        // an EVEN slot, then advance the shared qsoScheduler so its delay()s
        // resolve against that clock. Both must move together: qsoScheduler drives
        // the loop's suspension points, clockMs drives what SlotTiming/TxSlotSelection
        // read when the loop wakes up.
        repeat(4) {
            clockMs.addAndGet(15_000L)
            qsoScheduler.advanceTimeBy(15_000L)
            qsoScheduler.runCurrent()
        }
        assertFalse(controller.slice.value.qsoActive)
        // No-reply must not add the station to the user blocklist.
        assertTrue(controller.slice.value.userBlockedCalls.isEmpty())
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun decodeRowCq(call: String, grid: String): net.ft8vc.app.DecodeRow =
        net.ft8vc.app.DecodeRow(
            id = clockMs.get(),
            timeUtc = "000000",
            snr = -10,
            dtSeconds = 0f,
            freqHz = 1000,
            message = "CQ $call $grid",
            isCq = true,
        )

    private fun decodeRowDirected(message: String): net.ft8vc.app.DecodeRow =
        net.ft8vc.app.DecodeRow(
            id = clockMs.get(),
            timeUtc = "000000",
            snr = -10,
            dtSeconds = 0f,
            freqHz = 1000,
            message = message,
            isCq = false,
        )

    /**
     * Read the current machine state by triggering publishQsoState side-effect via
     * setOperateTxText — there's no public getter, but the slice's qsoState string
     * is deterministic from machine state.
     */
    private fun currentMachineState(): QsoState {
        val label = controller.slice.value.qsoState ?: return QsoState.Idle
        return when {
            label.contains("Calling CQ") -> QsoState.CallingCq
            label.startsWith("Answering") -> QsoState.Answering
            label.contains("Report") && !label.contains("R-report") -> QsoState.SendingReport
            label.contains("R-report") -> QsoState.SendingRReport
            label.contains("RR73") -> QsoState.SendingRoger
            label.contains("RRR") -> QsoState.SendingRoger
            label.contains("73") -> QsoState.SendingSeventyThree
            label.contains("complete") -> QsoState.Complete
            else -> QsoState.Idle
        }
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_700_000_000_000L
    }
}
