package net.ft8vc.core

/** Our role in the current QSO. */
enum class QsoRole { Initiator, Answerer }

/** QSO sequencing state from our perspective. */
enum class QsoState {
    /** No QSO in progress. */
    Idle,

    /** Initiator: transmitting `CQ`, waiting for a caller. */
    CallingCq,

    /** Answerer: transmitting `{dx} {me} {grid}`, waiting for a report. */
    Answering,

    /** Initiator: transmitting `{dx} {me} {report}`, waiting for R+report. */
    SendingReport,

    /** Answerer: transmitting `{dx} {me} R{report}`, waiting for RRR/RR73. */
    SendingRReport,

    /** Initiator: transmitting `{dx} {me} RRR`, waiting for 73. */
    SendingRoger,

    /** Answerer: transmitting `{dx} {me} 73` (terminal once sent). */
    SendingSeventyThree,

    /** QSO finished (ready to log). */
    Complete,
}

/**
 * Pure FT8 QSO auto-sequencer (no Android, no I/O) so it can be unit-tested.
 *
 * Drives the standard exchange from either side:
 * ```
 * Initiator:  CQ me grid -> (rx grid) -> dx me RPT -> (rx R-RPT) -> dx me RRR -> (rx 73) -> done
 * Answerer:   dx me grid -> (rx RPT)  -> dx me R-RPT -> (rx RRR/RR73) -> dx me 73 -> done
 * ```
 *
 * The caller transmits [txMessage] on its TX slots, calls [markTransmitted] after
 * each transmission, and feeds each completed RX slot's decodes to [onDecodes].
 */
class QsoMachine(
    private val myCall: String,
    private val myGrid: String,
    private val cqModifier: String? = null,
    /** Initiator sends RR73 and completes on transmit (WSJT-X style). OFF = v1.0 RRR. */
    private val initiatorRr73: Boolean = false,
) {
    var state: QsoState = QsoState.Idle
        private set
    var role: QsoRole? = null
        private set
    var dxCall: String? = null
        private set
    var dxGrid: String? = null
        private set
    var reportSent: Int? = null
        private set
    var reportRcvd: Int? = null
        private set

    /** TX cycles since the last decode advanced [state] (reset on [onDecodes] progress). */
    var unansweredTxCycles: Int = 0
        private set

    /** When true, [onDecodes] does not advance state (user owns the form). */
    var manualControl: Boolean = false
        private set

    /** Non-null overrides [txMessage] until cleared (typically after one TX). */
    var customTxMessage: String? = null
        private set

    val isActive: Boolean get() = state != QsoState.Idle && state != QsoState.Complete

    fun hasCustomOverride(): Boolean = !customTxMessage.isNullOrBlank()

    fun setManualControl(enabled: Boolean) {
        manualControl = enabled
    }

    fun setCustomMessage(message: String?) {
        customTxMessage = message?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearCustomMessage() {
        customTxMessage = null
    }

    /** Apply user-edited form fields and optional step change. Sets [manualControl] from [form]. */
    fun applyForm(form: QsoForm) {
        manualControl = form.manualControl
        customTxMessage = form.customMessage?.trim()?.takeIf { it.isNotEmpty() }
        dxCall = form.dxCall.trim().uppercase().takeIf { it.isNotEmpty() }
        dxGrid = form.dxGrid.trim().uppercase().takeIf { it.isNotEmpty() }
        reportSent = form.reportSent
        reportRcvd = form.reportRcvd
        applyStep(form.txStep)
    }

    fun applyStep(step: QsoTxStep) {
        when (step) {
            QsoTxStep.Idle -> reset()
            QsoTxStep.Cq -> {
                role = QsoRole.Initiator
                state = QsoState.CallingCq
            }
            QsoTxStep.Grid -> {
                role = QsoRole.Answerer
                state = QsoState.Answering
            }
            QsoTxStep.Report -> {
                if (role == null) role = QsoRole.Initiator
                state = QsoState.SendingReport
            }
            QsoTxStep.RReport -> {
                if (role == null) role = QsoRole.Answerer
                state = QsoState.SendingRReport
            }
            QsoTxStep.Roger -> {
                if (role == null) role = QsoRole.Initiator
                state = QsoState.SendingRoger
            }
            QsoTxStep.SeventyThree -> {
                if (role == null) role = QsoRole.Answerer
                state = QsoState.SendingSeventyThree
            }
            QsoTxStep.Custom -> { /* keep state; message from customTxMessage */ }
        }
    }

    fun formSnapshot(): QsoForm = QsoFormLogic.fromMachine(this)

    /** Begin calling CQ. */
    fun startCq() {
        reset()
        role = QsoRole.Initiator
        state = QsoState.CallingCq
    }

    /**
     * Begin answering a station's CQ. [snr] is the SNR we decoded their CQ at,
     * which becomes the report we send them.
     */
    fun answerCq(dxCall: String, dxGrid: String?, snr: Int) {
        reset()
        role = QsoRole.Answerer
        this.dxCall = dxCall
        this.dxGrid = dxGrid
        reportSent = snr
        state = QsoState.Answering
    }

    /** Initiator: answerer sent grid; next TX is our report. */
    fun resumeInitiatorAfterGridReply(dxCall: String, dxGrid: String, snr: Int) {
        reset()
        role = QsoRole.Initiator
        this.dxCall = dxCall
        this.dxGrid = dxGrid
        reportSent = snr
        state = QsoState.SendingReport
    }

    /** Answerer: we missed our grid TX; initiator sent [reportRcvd] — reply R+[reportSent]. */
    fun resumeAnswererAfterReport(dxCall: String, reportRcvd: Int, reportSent: Int) {
        reset()
        role = QsoRole.Answerer
        this.dxCall = dxCall
        this.reportSent = reportSent
        this.reportRcvd = reportRcvd
        state = QsoState.SendingRReport
    }

    /** Initiator: answerer sent R-[reportRcvd] — next TX is RRR/RR73. */
    fun resumeInitiatorAfterRReport(dxCall: String, reportRcvd: Int, reportSent: Int) {
        reset()
        role = QsoRole.Initiator
        this.dxCall = dxCall
        this.reportSent = reportSent
        this.reportRcvd = reportRcvd
        state = QsoState.SendingRoger
    }

    /** Answerer: initiator sent RRR/RR73 — next TX is 73. */
    fun resumeAnswererAfterRoger(dxCall: String) {
        reset()
        role = QsoRole.Answerer
        this.dxCall = dxCall
        state = QsoState.SendingSeventyThree
    }

    fun reset() {
        state = QsoState.Idle
        role = null
        dxCall = null
        dxGrid = null
        reportSent = null
        reportRcvd = null
        unansweredTxCycles = 0
        manualControl = false
        customTxMessage = null
    }

    /** The message to transmit on the next TX slot, or null if nothing to send. */
    fun txMessage(): String? {
        val custom = customTxMessage?.trim()
        if (!custom.isNullOrEmpty()) return custom
        val dx = dxCall
        return when (state) {
            QsoState.Idle, QsoState.Complete -> null
            QsoState.CallingCq -> QsoMessages.cq(myCall, myGrid, cqModifier)
            QsoState.Answering -> dx?.let { QsoMessages.reply(it, myCall, myGrid) }
            QsoState.SendingReport -> dx?.let { QsoMessages.report(it, myCall, reportSent ?: 0) }
            QsoState.SendingRReport -> dx?.let { QsoMessages.rReport(it, myCall, reportSent ?: 0) }
            QsoState.SendingRoger -> dx?.let {
                if (initiatorRr73) QsoMessages.rr73(it, myCall) else QsoMessages.rrr(it, myCall)
            }
            QsoState.SendingSeventyThree -> dx?.let { QsoMessages.bye73(it, myCall) }
        }
    }

    /** Call after a TX slot completes. Advances terminal states to Complete. */
    fun markTransmitted() {
        if (state == QsoState.SendingSeventyThree ||
            (initiatorRr73 && state == QsoState.SendingRoger)
        ) {
            state = QsoState.Complete
        }
    }

    /** [markTransmitted] plus no-reply cycle counting for abandon timeout. */
    fun recordTransmitted() {
        customTxMessage = null
        markTransmitted()
        if (state != QsoState.Complete && state != QsoState.Idle) {
            unansweredTxCycles++
        }
    }

    fun noReplyLimitExceeded(maxCycles: Int): Boolean =
        maxCycles > 0 && unansweredTxCycles >= maxCycles

    /** Snapshot for logging when [state] is [QsoState.Complete]. */
    fun snapshot(completedAtEpochMs: Long = System.currentTimeMillis()): QsoSnapshot? {
        if (state != QsoState.Complete) return null
        val dx = dxCall ?: return null
        return QsoSnapshot(
            myCall = myCall,
            myGrid = myGrid,
            dxCall = dx,
            dxGrid = dxGrid,
            reportSent = reportSent,
            reportRcvd = reportRcvd,
            role = role ?: QsoRole.Initiator,
            completedAtEpochMs = completedAtEpochMs,
        )
    }

    /**
     * Feed decodes from a completed RX slot. Advances the state when the expected
     * reply from [dxCall] (addressed to [myCall]) is present. Returns true if the
     * state changed.
     */
    fun onDecodes(
        decodes: List<QsoDecode>,
        answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
        excludedDx: Set<String> = emptySet(),
    ): Boolean {
        if (manualControl) return false
        val advanced = when (state) {
            QsoState.CallingCq -> handleCqReplies(decodes, answerPolicy, excludedDx)
            QsoState.Answering -> advanceIf(decodes) { rx ->
                (rx as? QsoRx.Report)?.takeIf { fromDx(it.target, it.sender) }?.let {
                    reportRcvd = it.snr
                    QsoState.SendingRReport
                }
            }
            QsoState.SendingReport -> advanceIf(decodes) { rx ->
                (rx as? QsoRx.RReport)?.takeIf { fromDx(it.target, it.sender) }?.let {
                    reportRcvd = it.snr
                    QsoState.SendingRoger
                }
            }
            QsoState.SendingRReport -> advanceIf(decodes) { rx ->
                when {
                    rx is QsoRx.Roger && fromDx(rx.target, rx.sender) -> QsoState.SendingSeventyThree
                    rx is QsoRx.RogerBye && fromDx(rx.target, rx.sender) -> QsoState.SendingSeventyThree
                    else -> null
                }
            }
            QsoState.SendingRoger -> advanceIf(decodes) { rx ->
                when {
                    rx is QsoRx.Bye && fromDx(rx.target, rx.sender) -> QsoState.Complete
                    rx is QsoRx.RogerBye && fromDx(rx.target, rx.sender) -> QsoState.Complete
                    else -> null
                }
            }
            else -> false
        }
        if (advanced) unansweredTxCycles = 0
        return advanced
    }

    private fun handleCqReplies(
        decodes: List<QsoDecode>,
        answerPolicy: AnswerPolicy,
        excludedDx: Set<String>,
    ): Boolean {
        val opp = AnswerSelector.selectOpportunity(
            myCall, myGrid, decodes, answerPolicy, excludedDx,
            allowedKinds = CQ_REPLY_KINDS,
        ) ?: return false
        when (opp.kind) {
            QsoResume.Kind.InitiatorGridReply ->
                resumeInitiatorAfterGridReply(opp.dxCall, opp.dxGrid ?: "", opp.snr)
            QsoResume.Kind.AnswererReport ->
                resumeAnswererAfterReport(opp.dxCall, opp.payloadReport ?: opp.snr, opp.snr)
            QsoResume.Kind.InitiatorRReport ->
                resumeInitiatorAfterRReport(opp.dxCall, opp.payloadReport ?: opp.snr, opp.snr)
            // Filtered out by CQ_REPLY_KINDS; kept exhaustive for the compiler.
            QsoResume.Kind.AnswererRoger -> return false
        }
        return true
    }

    private inline fun advanceIf(
        decodes: List<QsoDecode>,
        next: (QsoRx) -> QsoState?,
    ): Boolean {
        for (d in decodes) {
            val newState = next(QsoMessages.parse(d.message))
            if (newState != null) {
                state = newState
                return true
            }
        }
        return false
    }

    private fun fromDx(target: String, sender: String): Boolean {
        val dx = dxCall ?: return false
        return CallsignMatcher.matches(target, myCall) && CallsignMatcher.matches(sender, dx)
    }
}

/** Reply kinds an active CQ responds to. A stray RRR/RR73 has no exchange to repair. */
private val CQ_REPLY_KINDS = setOf(
    QsoResume.Kind.InitiatorGridReply,
    QsoResume.Kind.AnswererReport,
    QsoResume.Kind.InitiatorRReport,
)
