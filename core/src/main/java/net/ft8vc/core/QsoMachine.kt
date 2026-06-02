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

    val isActive: Boolean get() = state != QsoState.Idle && state != QsoState.Complete

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

    fun reset() {
        state = QsoState.Idle
        role = null
        dxCall = null
        dxGrid = null
        reportSent = null
        reportRcvd = null
    }

    /** The message to transmit on the next TX slot, or null if nothing to send. */
    fun txMessage(): String? {
        val dx = dxCall
        return when (state) {
            QsoState.Idle, QsoState.Complete -> null
            QsoState.CallingCq -> QsoMessages.cq(myCall, myGrid)
            QsoState.Answering -> dx?.let { QsoMessages.reply(it, myCall, myGrid) }
            QsoState.SendingReport -> dx?.let { QsoMessages.report(it, myCall, reportSent ?: 0) }
            QsoState.SendingRReport -> dx?.let { QsoMessages.rReport(it, myCall, reportSent ?: 0) }
            QsoState.SendingRoger -> dx?.let { QsoMessages.rrr(it, myCall) }
            QsoState.SendingSeventyThree -> dx?.let { QsoMessages.bye73(it, myCall) }
        }
    }

    /** Call after a TX slot completes. Advances terminal (73) state to Complete. */
    fun markTransmitted() {
        if (state == QsoState.SendingSeventyThree) {
            state = QsoState.Complete
        }
    }

    /**
     * Feed decodes from a completed RX slot. Advances the state when the expected
     * reply from [dxCall] (addressed to [myCall]) is present. Returns true if the
     * state changed.
     */
    fun onDecodes(decodes: List<QsoDecode>): Boolean = when (state) {
        QsoState.CallingCq -> handleCqReplies(decodes)
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

    private fun handleCqReplies(decodes: List<QsoDecode>): Boolean {
        for (d in decodes) {
            val rx = QsoMessages.parse(d.message)
            if (rx is QsoRx.GridReply && rx.target == myCall) {
                dxCall = rx.sender
                dxGrid = rx.grid
                reportSent = d.snr
                state = QsoState.SendingReport
                return true
            }
        }
        return false
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

    private fun fromDx(target: String, sender: String): Boolean =
        target == myCall && sender == dxCall
}
