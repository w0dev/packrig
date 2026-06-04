package net.ft8vc.core

/**
 * Display filter for the operate-screen decode list while monitoring.
 * Does not affect decoder output or [QsoMachine] inputs.
 */
object MonitorDecodeFilter {

    /** CQ calls and sign-offs (`73`, `RR73`). */
    fun isCqOrSignOff(message: String): Boolean = when (QsoMessages.parse(message)) {
        is QsoRx.Cq, is QsoRx.Bye, is QsoRx.RogerBye -> true
        else -> false
    }

    /**
     * Whether [message] should appear when the CQ/73 filter is enabled.
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
}
