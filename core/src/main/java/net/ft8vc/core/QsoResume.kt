package net.ft8vc.core

/**
 * Detects directed FT8 traffic to [myCall] and resumes [QsoMachine] at the matching state
 * (e.g. after Stop QSO while still monitoring).
 */
object QsoResume {

    enum class Kind {
        /** `{me} {dx} {grid}` — send report as CQ initiator. */
        InitiatorGridReply,

        /** `{me} {dx} {report}` — send R-report as answerer. */
        AnswererReport,

        /** `{me} {dx} R{report}` — send RRR as initiator. */
        InitiatorRReport,

        /** `{me} {dx} RRR` or RR73 — send 73 as answerer. */
        AnswererRoger,
    }

    data class Opportunity(
        val dxCall: String,
        val dxGrid: String?,
        val kind: Kind,
        val snr: Int,
    )

    /** First directed resume opportunity in [decodes], or null. */
    fun findOpportunity(myCall: String, decodes: List<QsoDecode>): Opportunity? {
        if (myCall.isBlank()) return null
        for (d in decodes) {
            val opp = opportunityFromDecode(myCall, d) ?: continue
            return opp
        }
        return null
    }

    fun opportunityFromDecode(myCall: String, decode: QsoDecode): Opportunity? {
        if (myCall.isBlank()) return null
        return when (val rx = QsoMessages.parse(decode.message)) {
            is QsoRx.GridReply if rx.target == myCall ->
                Opportunity(rx.sender, rx.grid, Kind.InitiatorGridReply, decode.snr)
            is QsoRx.Report if rx.target == myCall ->
                Opportunity(rx.sender, null, Kind.AnswererReport, decode.snr)
            is QsoRx.RReport if rx.target == myCall ->
                Opportunity(rx.sender, null, Kind.InitiatorRReport, decode.snr)
            is QsoRx.Roger if rx.target == myCall ->
                Opportunity(rx.sender, null, Kind.AnswererRoger, decode.snr)
            is QsoRx.RogerBye if rx.target == myCall ->
                Opportunity(rx.sender, null, Kind.AnswererRoger, decode.snr)
            else -> null
        }
    }

    fun isDirectedToMe(myCall: String, message: String): Boolean =
        opportunityFromDecode(myCall, QsoDecode(message, 0)) != null

    /** Configure [machine] for the next TX without running [onDecodes] first. */
    fun apply(machine: QsoMachine, opp: Opportunity) {
        when (opp.kind) {
            Kind.InitiatorGridReply ->
                machine.resumeInitiatorAfterGridReply(opp.dxCall, opp.dxGrid ?: "", opp.snr)
            Kind.AnswererReport ->
                machine.resumeAnswererAfterReport(opp.dxCall, opp.snr)
            Kind.InitiatorRReport ->
                machine.resumeInitiatorAfterRReport(opp.dxCall, opp.snr)
            Kind.AnswererRoger ->
                machine.resumeAnswererAfterRoger(opp.dxCall)
        }
    }
}
