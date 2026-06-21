package net.ft8vc.core

/**
 * Editable QSO fields for the Operate control sheet (WSJT-X-style form).
 * Synced with [QsoMachine] while a QSO loop is running; held in UI state when idle.
 */
data class QsoForm(
    val dxCall: String = "",
    val dxGrid: String = "",
    val reportSent: Int? = null,
    val reportRcvd: Int? = null,
    val txStep: QsoTxStep = QsoTxStep.Cq,
    val customMessage: String? = null,
    val manualControl: Boolean = false,
)

object QsoFormLogic {

    fun stepFromState(state: QsoState): QsoTxStep = when (state) {
        QsoState.Idle -> QsoTxStep.Idle
        QsoState.CallingCq -> QsoTxStep.Cq
        QsoState.Answering -> QsoTxStep.Grid
        QsoState.SendingReport -> QsoTxStep.Report
        QsoState.SendingRReport -> QsoTxStep.RReport
        QsoState.SendingRoger -> QsoTxStep.Roger
        QsoState.SendingSeventyThree -> QsoTxStep.SeventyThree
        QsoState.Complete -> QsoTxStep.Idle
    }

    fun stepLabel(step: QsoTxStep): String = step.label

    /** Compose the FT8 line for [form.txStep] using station identity and field values. */
    fun compose(
        myCall: String,
        myGrid: String,
        cqModifier: String?,
        form: QsoForm,
    ): String? {
        val dx = form.dxCall.trim().uppercase().takeIf { it.isNotEmpty() }
        val grid = form.dxGrid.trim().uppercase().takeIf { it.isNotEmpty() }
        val sent = form.reportSent ?: 0
        return when (form.txStep) {
            QsoTxStep.Idle -> null
            QsoTxStep.Cq -> QsoMessages.cq(myCall, myGrid, cqModifier)
            QsoTxStep.Grid -> dx?.let { QsoMessages.reply(it, myCall, myGrid) }
            QsoTxStep.Report -> dx?.let { QsoMessages.report(it, myCall, sent) }
            QsoTxStep.RReport -> dx?.let { QsoMessages.rReport(it, myCall, sent) }
            QsoTxStep.Roger -> dx?.let { QsoMessages.rrr(it, myCall) }
            QsoTxStep.SeventyThree -> dx?.let { QsoMessages.bye73(it, myCall) }
            QsoTxStep.Custom -> form.customMessage?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /** Message to show/transmit: custom override, else composed from step + fields. */
    fun effectiveMessage(
        myCall: String,
        myGrid: String,
        cqModifier: String?,
        form: QsoForm,
    ): String? {
        val custom = form.customMessage?.trim()?.takeIf { it.isNotEmpty() }
        if (form.txStep == QsoTxStep.Custom && custom != null) return custom
        if (form.manualControl && custom != null) return custom
        return compose(myCall, myGrid, cqModifier, form)
    }

    fun fromMachine(machine: QsoMachine): QsoForm = QsoForm(
        dxCall = machine.dxCall.orEmpty(),
        dxGrid = machine.dxGrid.orEmpty(),
        reportSent = machine.reportSent,
        reportRcvd = machine.reportRcvd,
        txStep = if (machine.hasCustomOverride()) QsoTxStep.Custom else stepFromState(machine.state),
        customMessage = machine.customTxMessage,
        manualControl = machine.manualControl,
    )

    fun parseReport(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val body = if (t.startsWith("R", ignoreCase = true)) t.drop(1) else t
        return body.toIntOrNull()
    }

    fun formatReport(snr: Int?): String =
        snr?.let { QsoMessages.formatReport(it) } ?: ""
}
