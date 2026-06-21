package net.ft8vc.core

/**
 * Picks which station to work when several decodes qualify in the same slot.
 * Used for CQ pileups, answer-when-called, and auto-answer-CQ.
 */
object AnswerSelector {

    fun selectGridReply(
        myCall: String,
        myGrid: String,
        decodes: List<QsoDecode>,
        policy: AnswerPolicy,
        excludedDx: Set<String> = emptySet(),
    ): QsoDecode? {
        val candidates = gridRepliesToMe(myCall, decodes)
            .filterNot { isExcluded(it.dxCall, excludedDx) }
        if (candidates.isEmpty()) return null
        return pick(candidates, myGrid, policy) { it.grid }.decode
    }

    fun selectOpportunity(
        myCall: String,
        myGrid: String,
        decodes: List<QsoDecode>,
        policy: AnswerPolicy,
        excludedDx: Set<String> = emptySet(),
    ): QsoResume.Opportunity? {
        val candidates = decodes.mapNotNull { d ->
            QsoResume.opportunityFromDecode(myCall, d)?.let { opp ->
                Candidate(d, opp.dxCall, opp.dxGrid ?: gridFromOpportunity(opp, d))
            }
        }.filterNot { isExcluded(it.dxCall, excludedDx) }
        if (candidates.isEmpty()) return null
        val picked = pick(candidates, myGrid, policy) { it.grid }
        return QsoResume.opportunityFromDecode(myCall, picked.decode)
    }

    /** Pick a CQ to answer (not our own CQ). [dxGrid] may be null on the CQ message. */
    fun selectCq(
        myCall: String,
        myGrid: String,
        decodes: List<QsoDecode>,
        policy: AnswerPolicy,
        excludedDx: Set<String> = emptySet(),
    ): QsoDecode? {
        if (myCall.isBlank()) return null
        val candidates = decodes.mapNotNull { d ->
            when (val rx = QsoMessages.parse(d.message)) {
                is QsoRx.Cq if !callsignMatches(rx.call, myCall) ->
                    Candidate(d, rx.call, rx.grid)
                else -> null
            }
        }.filterNot { isExcluded(it.dxCall, excludedDx) }
        if (candidates.isEmpty()) return null
        return pick(candidates, myGrid, policy) { it.grid }.decode
    }

    private data class Candidate(
        val decode: QsoDecode,
        val dxCall: String,
        val grid: String?,
    )

    private fun gridRepliesToMe(myCall: String, decodes: List<QsoDecode>): List<Candidate> =
        decodes.mapNotNull { d ->
            when (val rx = QsoMessages.parse(d.message)) {
                is QsoRx.GridReply if callsignMatches(rx.target, myCall) ->
                    Candidate(d, rx.sender, rx.grid)
                else -> null
            }
        }

    private fun gridFromOpportunity(opp: QsoResume.Opportunity, decode: QsoDecode): String? =
        opp.dxGrid ?: when (val rx = QsoMessages.parse(decode.message)) {
            is QsoRx.GridReply -> rx.grid
            else -> null
        }

    private fun pick(
        candidates: List<Candidate>,
        myGrid: String,
        policy: AnswerPolicy,
        gridOf: (Candidate) -> String?,
    ): Candidate = when (policy) {
        AnswerPolicy.FIRST -> candidates.first()
        AnswerPolicy.BEST_SNR -> candidates.maxWith(
            compareBy<Candidate> { it.decode.snr }.thenBy { candidates.indexOf(it) },
        )
        AnswerPolicy.FURTHEST -> candidates.maxWith(
            compareBy<Candidate> { distanceScore(myGrid, gridOf(it)) }
                .thenBy { it.decode.snr }
                .thenBy { candidates.indexOf(it) },
        )
    }

    /** Higher is farther; invalid/missing grids sort last. */
    private fun distanceScore(myGrid: String, dxGrid: String?): Double =
        dxGrid?.let { MaidenheadGrid.distanceKm(myGrid, it) } ?: -1.0

    private fun isExcluded(dxCall: String, excludedDx: Set<String>): Boolean {
        if (excludedDx.isEmpty()) return false
        val base = dxCall.substringBefore('/').substringBefore('-').uppercase()
        return excludedDx.any { it.equals(dxCall, ignoreCase = true) || it.equals(base, ignoreCase = true) }
    }

    private fun callsignMatches(token: String, myCall: String): Boolean {
        if (token.equals(myCall, ignoreCase = true)) return true
        val myBase = myCall.substringBefore('/').substringBefore('-')
        val tokenBase = token.substringBefore('/').substringBefore('-')
        return myBase.isNotEmpty() &&
            myBase.any(Char::isDigit) &&
            myBase.equals(tokenBase, ignoreCase = true)
    }
}
