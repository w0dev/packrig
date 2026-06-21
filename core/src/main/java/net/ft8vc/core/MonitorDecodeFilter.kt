package net.ft8vc.core

/**
 * Display filter for the operate-screen decode list while monitoring.
 * Does not affect decoder output or [QsoMachine] inputs.
 *
 * **Operate** mode shows:
 * - All CQ calls on the band
 * - Any decode to/from your callsign
 * - Active QSO partner traffic
 * - Decodes within [TX_TONE_WINDOW_HZ] of your TX tone
 */
object MonitorDecodeFilter {

    /** Hz either side of [txToneHz] for the operating passband slice. */
    const val TX_TONE_WINDOW_HZ = 150

    private val WHITESPACE = Regex("\\s+")

    /** CQ calls and sign-offs (`73`, `RR73`). */
    fun isCqOrSignOff(message: String): Boolean = when (QsoMessages.parse(message)) {
        is QsoRx.Cq, is QsoRx.Bye, is QsoRx.RogerBye -> true
        else -> false
    }

    /** Directed FT8 message where [myCall] is the TO or FROM callsign. */
    fun messageInvolvesMyCall(message: String, myCall: String): Boolean {
        if (myCall.isBlank()) return false
        val tokens = message.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens[0] == "CQ") return false
        if (tokens.size < 2) return false
        return callsignMatches(tokens[0], myCall) || callsignMatches(tokens[1], myCall)
    }

    fun nearTxTone(freqHz: Int, txToneHz: Int): Boolean =
        kotlin.math.abs(freqHz - txToneHz) <= TX_TONE_WINDOW_HZ

    /**
     * Operate focus: band CQs, your QSO traffic, partner, and activity on your TX tone.
     */
    fun visibleInOperateMode(
        message: String,
        isCq: Boolean,
        myCall: String,
        freqHz: Int,
        txToneHz: Int,
        qsoDx: String?,
        qsoActive: Boolean,
    ): Boolean {
        if (isCq) return true
        if (messageInvolvesMyCall(message, myCall)) return true
        if (qsoActive && qsoDx != null && message.contains(qsoDx)) return true
        if (nearTxTone(freqHz, txToneHz)) return true
        return false
    }

    /**
     * Combined display filter: [viewMode] then optional CQ/73 narrowing (All mode only).
     */
    fun visibleForDisplay(
        message: String,
        isCq: Boolean,
        myCall: String,
        freqHz: Int,
        txToneHz: Int,
        viewMode: DecodeViewMode,
        cq73OnlyFilter: Boolean,
        qsoDx: String?,
        qsoActive: Boolean,
    ): Boolean {
        if (viewMode == DecodeViewMode.OPERATE &&
            !visibleInOperateMode(message, isCq, myCall, freqHz, txToneHz, qsoDx, qsoActive)
        ) {
            return false
        }
        if (viewMode == DecodeViewMode.ALL && cq73OnlyFilter) {
            return visible(message, cq73OnlyFilter, qsoDx, qsoActive)
        }
        return true
    }

    /**
     * Whether [message] should appear when the CQ/73 filter is enabled (All mode).
     * During an active QSO, all traffic involving [qsoDx] remains visible.
     */
    fun visible(
        message: String,
        filterEnabled: Boolean,
        qsoDx: String?,
        qsoActive: Boolean,
    ): Boolean {
        if (!filterEnabled) return true
        if (isCqOrSignOff(message)) return true
        if (qsoActive && qsoDx != null && message.contains(qsoDx)) return true
        return false
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
