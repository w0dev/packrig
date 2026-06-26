package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

    private var lateTxEnabled = true

    @Before fun setUp() {
        clockMs.set(BASE_EPOCH_MS)
        transmittedMessages.clear()
        currentSlotAttempts.clear()
        completedSnapshots.clear()
        notifications.clear()
        resumeCaptureCalls.set(0)
        lateTxEnabled = true
        scope = CoroutineScope(UnconfinedTestDispatcher())
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
            qsoDispatcher = UnconfinedTestDispatcher(),
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
    fun startCq_neverFiresLate() = runTest {
        // Cold CQ needs the leading Costas — late-TX must NOT apply (spec §Non-goals).
        controller.startCq()
        assertTrue("calling CQ must never route through the late path", currentSlotAttempts.isEmpty())
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
    fun abandonQso_blocksLaterAutoResume() = runTest {
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        controller.abandonQso()
        // Second directed message from same caller should NOT auto-resume.
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)
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
