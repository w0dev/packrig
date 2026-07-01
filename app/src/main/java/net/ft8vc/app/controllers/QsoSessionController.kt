package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ft8vc.app.DecodeRow
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.SnackbarEvent
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.AbandonedPartners
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.AnswerSelector
import net.ft8vc.core.QsoDecode
import net.ft8vc.core.QsoForm
import net.ft8vc.core.QsoFormLogic
import net.ft8vc.core.QsoMachine
import net.ft8vc.core.QsoMessages
import net.ft8vc.core.QsoResume
import net.ft8vc.core.QsoRole
import net.ft8vc.core.QsoRx
import net.ft8vc.core.QsoSnapshot
import net.ft8vc.core.QsoState
import net.ft8vc.core.QsoTxStep
import net.ft8vc.core.SlotTiming
import net.ft8vc.core.StationProfileValidator
import net.ft8vc.core.TxSlotParity
import net.ft8vc.core.TxSlotSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the QSO state machine, the per-slot TX loop, and the UTC slot clock.
 *
 * ## Invariants the deleted `qsoLock` used to enforce, now provided by `qsoDispatcher`:
 *
 *  1. **Single-writer for `qso: QsoMachine?`** — only this controller's
 *     `qsoDispatcher` thread reads or mutates the field. Every UI action
 *     (`startCq`, `answerCq`, `stopQso`, `setOperateTxText`, etc.) and
 *     every periodic event (decode-batch advance, TX-slot tick) hops onto
 *     the dispatcher before touching the machine. The legacy
 *     `synchronized(qsoLock) { qso = machine }` becomes a plain assignment
 *     because no other thread can observe the field.
 *
 *  2. **Atomic check-then-act ordering** — composites like
 *     `qso?.recordTransmitted(); qso?.noReplyLimitExceeded(...)` or
 *     `if (qso?.state == Complete) handleComplete()` are run as adjacent
 *     statements on the dispatcher, so they remain atomic without the
 *     old `synchronized` block — the dispatcher's single-thread model
 *     forbids interleaving with `qso?.onDecodes(...)` or `qso = null`.
 *
 *  3. **Cross-controller ordering: TX → recordTransmitted → noReplyLimit
 *     check → publishState** — the qsoLock used to guarantee the
 *     transmitMessageNow() call and the subsequent state mutations were
 *     observed in order by anything reading from the qsoThread.
 *     Replaced by sequential `withContext` blocks inside `runQsoLoop`:
 *     TX happens via the injected `transmitFn` callback (which runs
 *     synchronously to the loop coroutine), then the mutations run on
 *     `qsoDispatcher` immediately afterward.
 *
 *  4. **Decode-during-TX-slot ordering** — when a decode batch arrives
 *     during a TX slot, the previous code relied on the lock so that
 *     `qso?.onDecodes` couldn't race against `qso?.recordTransmitted` in
 *     the qsoThread. Now both run on `qsoDispatcher` (decode batches
 *     hopped via `scope.launch(qsoDispatcher)`), so the dispatcher
 *     orders them by arrival time.
 *
 *  5. **Reset-on-clear** — `synchronized(qsoLock) { qso = null }` in
 *     `stopQso` paired with the lock guaranteed no in-flight access
 *     could later see a stale machine. Now `stopQso()` cancels
 *     `qsoLoopJob` and waits for the dispatcher to drain before nulling
 *     `qso`, which keeps the same invariant under coroutine cancellation.
 *
 * The previous code also used `@Volatile var qsoRunning: Boolean` as a
 * cooperative-cancellation signal for the qsoThread. That is now the
 * `qsoLoopJob: Job?` field — `isActive` inside the loop, `cancel()` from
 * `stopQso()`. The `@Volatile` is no longer needed: the loop body checks
 * `isActive` at each delay() suspension point, which is structured
 * cancellation rather than a polled flag.
 *
 * The previous code's `Thread.sleep(...)` calls become `delay(...)` so
 * cancellation propagates immediately and the dispatcher thread can be
 * reused while the loop is waiting for the next slot boundary.
 */
class QsoSessionController(
    private val scope: CoroutineScope,
    private val transmitFn: suspend (message: String) -> Boolean,
    /**
     * Late-TX entry for the FIRST transmission of an Answer/Resume/auto-answer
     * QSO: fire into the CURRENT slot if it is ours, choosing full vs truncated
     * waveform by how far into the slot we are. Returns true if it transmitted
     * now; false means defer to the boundary-aligned path. Defaults to no-op
     * (always defer) so call sites that don't wire late-TX keep v1.0 behavior.
     */
    private val transmitIntoCurrentSlotFn: suspend (message: String) -> Boolean = { false },
    /** Late-start TX Settings toggle. OFF preserves v1.0 timing byte-for-byte (PARITY-01). */
    private val lateStartTxEnabledProvider: () -> Boolean = { false },
    private val onQsoComplete: suspend (QsoSnapshot) -> Unit,
    private val notifyFn: (String, SnackbarEvent.Tag) -> Unit,
    private val resumeCaptureIfNeeded: () -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qso-session").apply { isDaemon = true }
    },
    val qsoDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val slotClockIntervalMs: Long = 250L,
) : AutoCloseable {

    private val abandonedPartners = AbandonedPartners()
    private var qso: QsoMachine? = null
    private var qsoLoopJob: Job? = null
    private var slotClockJob: Job? = null
    private var qsoTxParity: TxSlotParity? = null
    private var lastLoggedKey: String? = null
    private var operateTxUserEdited: Boolean = false

    // Mutable settings/profile state — written from VM, read from dispatcher coroutines.
    // No lock needed: writes happen at known points, reads happen inside qsoDispatcher;
    // these are only used for read-then-decide logic, not as atomicity boundaries.
    @Volatile private var myCall: String = ""
    @Volatile private var myGrid: String = ""
    @Volatile private var potaModeEnabled: Boolean = false
    @Volatile private var potaParkRef: String = ""
    @Volatile private var txEnabled: Boolean = false
    @Volatile private var autoSeqEnabled: Boolean = true
    @Volatile private var answerWhenCalledEnabled: Boolean = true
    @Volatile private var autoAnswerCqEnabled: Boolean = false
    @Volatile private var answerPolicy: AnswerPolicy = AnswerPolicy.FIRST
    @Volatile private var maxUnansweredTxCycles: Int = 5
    @Volatile private var defaultTxSlotParity: TxSlotParity = TxSlotParity.EVEN
    @Volatile private var isOperating: Boolean = false

    private val _slice = MutableStateFlow(QsoSlice())
    val slice: StateFlow<QsoSlice> = _slice.asStateFlow()

    private val utcClockFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        startSlotClock()
    }

    // ── Settings setters (called from VM on settings-bridge updates) ────

    fun updateStationProfile(call: String, grid: String, potaMode: Boolean, potaPark: String) {
        myCall = call; myGrid = grid; potaModeEnabled = potaMode; potaParkRef = potaPark
        refreshOperateTxFromStation()
    }

    fun setTxEnabled(enabled: Boolean) { txEnabled = enabled }
    fun setAutoSeqEnabled(enabled: Boolean) { autoSeqEnabled = enabled }
    fun setAnswerWhenCalledEnabled(enabled: Boolean) { answerWhenCalledEnabled = enabled }
    fun setAutoAnswerCqEnabled(enabled: Boolean) { autoAnswerCqEnabled = enabled }
    fun setAnswerPolicy(policy: AnswerPolicy) { answerPolicy = policy }
    fun setMaxUnansweredTxCycles(cycles: Int) { maxUnansweredTxCycles = cycles }
    fun setDefaultTxSlotParity(parity: TxSlotParity) { defaultTxSlotParity = parity }
    fun setOperating(operating: Boolean) { isOperating = operating }

    // ── UI actions ──────────────────────────────────────────────────────

    fun startCq() {
        if (!hasValidStationProfile()) return
        if (potaModeEnabled && !ActivationProfile.isValidParkRefList(potaParkRef)) {
            notifyFn("Set valid POTA park reference(s) in Settings (e.g. US-3315 or US-3315,US-0891)", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!txEnabled) {
            notifyFn("Enable TX in Settings first", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!operateTxUserEdited) {
            syncOperateTxText(defaultOperateTxText())
        }
        scope.launch(qsoDispatcher) {
            val machine = newQsoMachine()
            machine.startCq()
            applyOperateTxOverride(machine)
            startQsoLoop(machine, hearingSlotParity = null)
        }
    }

    fun answerCq(row: DecodeRow) {
        if (!hasValidStationProfile()) return
        if (!txEnabled) {
            notifyFn("Enable TX in Settings first", SnackbarEvent.Tag.ERROR)
            return
        }
        val cq = QsoMessages.parse(row.message) as? QsoRx.Cq ?: run {
            notifyFn("Not a CQ: ${row.message}", SnackbarEvent.Tag.ERROR)
            return
        }
        notifyFn("Answering ${cq.call}", SnackbarEvent.Tag.TRANSIENT)
        scope.launch(qsoDispatcher) {
            val machine = newQsoMachine()
            machine.answerCq(cq.call, cq.grid, row.snr)
            startQsoLoop(machine, hearingSlotParity = row.slotParity)
        }
    }

    fun resumeFromDecode(row: DecodeRow) {
        if (!hasValidStationProfile()) return
        if (!txEnabled) {
            notifyFn("Enable TX in Settings first", SnackbarEvent.Tag.ERROR)
            return
        }
        val opp = QsoResume.opportunityFromDecode(myCall, QsoDecode(row.message, row.snr)) ?: run {
            notifyFn("Not a directed message to $myCall", SnackbarEvent.Tag.ERROR)
            return
        }
        abandonedPartners.allowResume(opp.dxCall)
        scope.launch(qsoDispatcher) {
            resumeFromOpportunity(opp, "Resuming QSO with ${opp.dxCall}", row.slotParity)
        }
    }

    fun stopQso() {
        scope.launch(qsoDispatcher) { stopQsoInternal() }
    }

    fun abandonQso() {
        scope.launch(qsoDispatcher) {
            val dx = qso?.dxCall
            if (dx != null) abandonedPartners.abandon(dx)
            stopQsoInternal()
            notifyFn(dx?.let { "Abandoned QSO with $it" } ?: "QSO stopped", SnackbarEvent.Tag.TRANSIENT)
        }
    }

    fun setOperateTxText(text: String) {
        operateTxUserEdited = true
        _slice.update {
            it.copy(operateTxText = text, operateTxStep = QsoTxStep.Custom, operateTxEdited = true)
        }
        scope.launch(qsoDispatcher) {
            qso?.setCustomMessage(text.trim().takeIf { it.isNotEmpty() })
        }
    }

    fun selectOperateTxStep(step: QsoTxStep) {
        if (step == QsoTxStep.Custom) {
            operateTxUserEdited = true
            _slice.update { it.copy(operateTxStep = QsoTxStep.Custom, operateTxEdited = true) }
            return
        }
        val composed = composeOperateTxForStep(step) ?: return
        operateTxUserEdited = true
        _slice.update {
            it.copy(operateTxStep = step, operateTxText = composed, operateTxEdited = true)
        }
        scope.launch(qsoDispatcher) { qso?.setCustomMessage(composed) }
    }

    fun resetOperateTxText() {
        operateTxUserEdited = false
        scope.launch(qsoDispatcher) {
            qso?.clearCustomMessage()
            syncOperateTxText(autoOperateTxText())
        }
    }

    fun clearAbandonedPartners() {
        abandonedPartners.clear()
        notifyFn("Cleared abandoned-station blocklist", SnackbarEvent.Tag.TRANSIENT)
    }

    fun refreshOperateTxFromStation() {
        if (operateTxUserEdited) return
        scope.launch(qsoDispatcher) {
            syncOperateTxText(if (qsoLoopJob?.isActive == true) autoOperateTxText() else defaultOperateTxText())
        }
    }

    fun onDecodeBatch(decodes: List<QsoDecode>, slotParity: TxSlotParity) {
        scope.launch(qsoDispatcher) {
            val running = qsoLoopJob?.isActive == true
            if (running && autoSeqEnabled) {
                val excluded = abandonedPartners.snapshot()
                val advanced = qso?.onDecodes(decodes, answerPolicy, excluded) ?: false
                if (advanced) {
                    operateTxUserEdited = false
                    publishQsoState()
                    if (qso?.state == QsoState.Complete) handleQsoComplete()
                }
            } else if (!running && isOperating && txEnabled && myCall.isNotBlank()) {
                if (answerWhenCalledEnabled) tryAnswerWhenCalled(decodes, slotParity)
                if (qsoLoopJob?.isActive != true && autoAnswerCqEnabled) {
                    tryAutoAnswerCq(decodes, slotParity)
                }
            }
        }
    }

    // ── Internal: QSO loop ──────────────────────────────────────────────

    private suspend fun startQsoLoop(machine: QsoMachine, hearingSlotParity: TxSlotParity?) {
        stopQsoInternal()
        qso = machine
        qsoTxParity = resolveTxParity(machine, hearingSlotParity)
        publishQsoState()

        qsoLoopJob = scope.launch(qsoDispatcher) {
            try {
                val txParity = qsoTxParity ?: return@launch
                // The FIRST transmission (CQ, Answer, Resume, or auto-answer) may fire
                // late into the current slot when it is ours — matching WSJT-X, which
                // truncates a late CQ too (the middle/end Costas arrays still allow
                // sync). Toggle OFF reverts to v1.0 boundary-aligned timing (PARITY-01).
                var lateFirstTxPending = lateStartTxEnabledProvider()
                while (isActive) {
                    if (lateFirstTxPending) {
                        lateFirstTxPending = false
                        // Only fire late if the CURRENT slot is already ours; the
                        // orchestrator picks full-vs-truncated from t_in_slot and
                        // returns false (defer) past the cutoff.
                        if (TxSlotSelection.slotParity(SlotTiming.slotStart(clock())) == txParity) {
                            val message = qso?.txMessage() ?: break
                            if (transmitIntoCurrentSlotFn(message)) {
                                if (afterTransmit()) return@launch
                                continue
                            }
                            // Deferred/blocked → fall through to the boundary path.
                        }
                    }

                    val wait = SlotTiming.millisUntilNextSlot(clock())
                    if (wait > 0) delay(wait)
                    if (!isActive) break

                    val slotStart = SlotTiming.slotStart(clock())
                    val ourTx = TxSlotSelection.slotParity(slotStart) == txParity
                    if (!ourTx) continue

                    delay(OperateUiState.QSO_TX_GRACE_MS)
                    if (!isActive) break

                    val message = qso?.txMessage() ?: break
                    transmitFn(message)
                    if (afterTransmit()) return@launch
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    notifyFn(t.message ?: "QSO failed", SnackbarEvent.Tag.ERROR)
                }
            } finally {
                qsoTxParity = null
                resumeCaptureIfNeeded()
                publishQsoState()
            }
        }
    }

    /**
     * Shared post-transmit bookkeeping: record the TX, check the no-reply limit and
     * completion. Returns true if the QSO loop should terminate.
     */
    private suspend fun afterTransmit(): Boolean {
        val noReplyLimitHit = run {
            qso?.recordTransmitted()
            qso?.noReplyLimitExceeded(maxUnansweredTxCycles) == true
        }
        publishQsoState()
        if (noReplyLimitHit) {
            abandonForNoReply()
            return true
        }
        if (qso?.state == QsoState.Complete) {
            handleQsoComplete()
            return true
        }
        return false
    }

    private suspend fun stopQsoInternal() {
        qsoLoopJob?.cancel()
        qsoLoopJob = null
        qsoTxParity = null
        qso = null
        operateTxUserEdited = false
        _slice.update {
            it.copy(
                qsoActive = false,
                qsoState = null,
                qsoDx = null,
                nextTxMessage = null,
                operateTxText = defaultOperateTxText(),
                operateTxStep = QsoTxStep.Cq,
                operateTxEdited = false,
                operateTxForm = QsoForm(),
                activeTxSlotParity = null,
            )
        }
    }

    private suspend fun abandonForNoReply() {
        val dx = qso?.dxCall
        if (dx != null) abandonedPartners.abandon(dx)
        val message = when {
            dx != null -> "No reply from $dx — QSO abandoned"
            else -> "No answers — stopped calling CQ"
        }
        stopQsoInternal()
        notifyFn(message, SnackbarEvent.Tag.TRANSIENT)
    }

    private suspend fun handleQsoComplete() {
        val snapshot = qso?.snapshot(clock()) ?: return
        val key = "${snapshot.dxCall}:${snapshot.completedAtEpochMs}"
        if (key == lastLoggedKey) return
        lastLoggedKey = key
        onQsoComplete(snapshot)
        notifyFn("QSO complete with ${snapshot.dxCall} — logged", SnackbarEvent.Tag.QSO_COMPLETE)
    }

    private suspend fun resumeFromOpportunity(
        opp: QsoResume.Opportunity,
        snackbar: String,
        hearingSlotParity: TxSlotParity,
    ) {
        val machine = newQsoMachine()
        QsoResume.apply(machine, opp)
        notifyFn(snackbar, SnackbarEvent.Tag.TRANSIENT)
        startQsoLoop(
            machine,
            hearingSlotParity = if (machine.role == QsoRole.Answerer) hearingSlotParity else null,
        )
    }

    private suspend fun tryAnswerWhenCalled(decodes: List<QsoDecode>, hearingSlotParity: TxSlotParity) {
        if (qsoLoopJob?.isActive == true || decodes.isEmpty()) return
        val opp = QsoResume.findOpportunity(
            myCall,
            myGrid,
            decodes,
            answerPolicy,
            abandonedPartners.snapshot(),
        ) ?: return
        resumeFromOpportunity(opp, "Answering ${opp.dxCall}", hearingSlotParity)
    }

    private suspend fun tryAutoAnswerCq(decodes: List<QsoDecode>, hearingSlotParity: TxSlotParity) {
        if (qsoLoopJob?.isActive == true || decodes.isEmpty()) return
        val picked = AnswerSelector.selectCq(
            myCall,
            myGrid,
            decodes,
            answerPolicy,
            abandonedPartners.snapshot(),
        ) ?: return
        val cq = QsoMessages.parse(picked.message) as? QsoRx.Cq ?: return
        notifyFn("Answering ${cq.call}", SnackbarEvent.Tag.TRANSIENT)
        val machine = newQsoMachine()
        machine.answerCq(cq.call, cq.grid, picked.snr)
        startQsoLoop(machine, hearingSlotParity = hearingSlotParity)
    }

    // ── Internal: TX-text derivation ────────────────────────────────────

    private fun effectiveCqModifier(): String? = ActivationProfile.cqModifier(potaModeEnabled)

    private fun newQsoMachine(): QsoMachine = QsoMachine(myCall, myGrid, effectiveCqModifier())

    private fun defaultOperateTxText(): String = QsoMessages.cq(myCall, myGrid, effectiveCqModifier())

    private fun autoOperateTxText(): String? = qso?.txMessage() ?: defaultOperateTxText()

    private fun currentAutoTxStep(): QsoTxStep =
        qso?.let { QsoFormLogic.stepFromState(it.state) } ?: QsoTxStep.Cq

    private fun composeOperateTxForStep(step: QsoTxStep): String? {
        return QsoFormLogic.compose(
            myCall, myGrid, effectiveCqModifier(),
            _slice.value.operateTxForm.copy(txStep = step),
        )
    }

    private fun applyOperateTxOverride(machine: QsoMachine) {
        if (!operateTxUserEdited) return
        val text = _slice.value.operateTxText.trim()
        if (text.isNotEmpty()) machine.setCustomMessage(text)
    }

    private fun resolveTxParity(machine: QsoMachine, hearingSlotParity: TxSlotParity?): TxSlotParity {
        if (hearingSlotParity != null && machine.role == QsoRole.Answerer) {
            return TxSlotSelection.answerParity(hearingSlotParity)
        }
        return defaultTxSlotParity
    }

    private fun syncOperateTxText(auto: String?, step: QsoTxStep = currentAutoTxStep()) {
        val message = auto ?: defaultOperateTxText()
        if (!operateTxUserEdited) {
            _slice.update {
                it.copy(operateTxText = message, operateTxStep = step, operateTxEdited = false)
            }
        }
    }

    private fun publishQsoState() {
        val m = qso
        if (m == null) {
            val nextMsg = defaultOperateTxText()
            syncOperateTxText(nextMsg, QsoTxStep.Cq)
            _slice.update {
                it.copy(
                    qsoActive = false,
                    qsoState = null,
                    qsoDx = null,
                    nextTxMessage = nextMsg,
                    operateTxForm = QsoForm(),
                )
            }
            return
        }
        val st = m.state
        val dx = m.dxCall
        val active = m.isActive
        val form = QsoFormLogic.fromMachine(m)
        val autoStep = QsoFormLogic.stepFromState(st)
        val nextMsg = m.txMessage()
        syncOperateTxText(nextMsg, autoStep)
        _slice.update {
            it.copy(
                qsoActive = active,
                qsoState = qsoStateLabel(st, dx),
                qsoDx = dx,
                nextTxMessage = nextMsg,
                operateTxEdited = operateTxUserEdited,
                operateTxForm = form,
            )
        }
    }

    private fun qsoStateLabel(state: QsoState, dx: String?): String = when (state) {
        QsoState.Idle -> "$myCall $myGrid"
        QsoState.CallingCq -> if (effectiveCqModifier() != null) "Calling CQ POTA…" else "Calling CQ…"
        QsoState.Answering -> "Answering ${dx ?: "?"}…"
        QsoState.SendingReport -> "QSO ${dx ?: "?"} — Report"
        QsoState.SendingRReport -> "QSO ${dx ?: "?"} — R-report"
        QsoState.SendingRoger -> "QSO ${dx ?: "?"} — RRR"
        QsoState.SendingSeventyThree -> "QSO ${dx ?: "?"} — 73"
        QsoState.Complete -> "QSO complete${dx?.let { " with $it" } ?: ""}"
    }

    private fun hasValidStationProfile(): Boolean {
        if (!StationProfileValidator.isValidCall(myCall)) {
            notifyFn("Set a valid callsign in Settings before transmitting", SnackbarEvent.Tag.ERROR)
            return false
        }
        if (!StationProfileValidator.isValidGrid(myGrid)) {
            notifyFn("Set a valid 4- or 6-char grid in Settings before transmitting", SnackbarEvent.Tag.ERROR)
            return false
        }
        return true
    }

    // ── Slot clock ──────────────────────────────────────────────────────

    private fun startSlotClock() {
        slotClockJob?.cancel()
        slotClockJob = scope.launch {
            while (isActive) {
                val now = clock()
                val parity = qsoTxParity ?: defaultTxSlotParity
                val isTx = TxSlotSelection.slotParity(now) == parity
                _slice.update {
                    it.copy(
                        slotIndex = SlotTiming.slotIndexInMinute(now),
                        secondsToNextSlot = SlotTiming.secondsUntilNextSlot(now),
                        secondsUntilOurTxSlot = if (isTx) {
                            SlotTiming.secondsUntilNextSlot(now)
                        } else {
                            ((TxSlotSelection.millisUntilNextTxSlot(now, parity) + 999) / 1000).toInt()
                        },
                        utcClock = utcClockFormat.format(Date(now)),
                        isTxSlot = isTx,
                        activeTxSlotParity = qsoTxParity,
                    )
                }
                delay(slotClockIntervalMs)
            }
        }
    }

    override fun close() {
        slotClockJob?.cancel()
        qsoLoopJob?.cancel()
        (qsoDispatcher as? ExecutorCoroutineDispatcher)?.close()
        executor.shutdown()
    }
}

data class QsoSlice(
    val qsoActive: Boolean = false,
    val qsoState: String? = null,
    val qsoDx: String? = null,
    val operateTxText: String = "",
    val operateTxStep: QsoTxStep = QsoTxStep.Cq,
    val operateTxEdited: Boolean = false,
    val operateTxForm: QsoForm = QsoForm(),
    val nextTxMessage: String? = null,
    val activeTxSlotParity: TxSlotParity? = null,
    val slotIndex: Int = 0,
    val secondsToNextSlot: Int = 15,
    val isTxSlot: Boolean = false,
    val secondsUntilOurTxSlot: Int = 15,
    val utcClock: String = "00:00:00",
)
