package net.ft8vc.core

/**
 * Decorates decode messages with a single non-color glyph so the row type is
 * visible to operators who cannot rely on color (color-blindness, dim screens).
 *
 * - `●` — CQ
 * - `→` — directed to my callsign (idle, not in QSO)
 * - `▸` — current QSO partner during an active QSO
 * - blank — every other decode
 *
 * Pure function so the same logic can be reused in tests and previews.
 */
object DecodePrefix {

    const val CQ = "● "
    const val TO_ME = "→ "
    const val PARTNER = "▸ "

    fun prefixFor(
        message: String,
        isCq: Boolean,
        isToMe: Boolean,
        qsoActive: Boolean,
        qsoDx: String?,
    ): String {
        val isPartner = qsoActive && qsoDx != null && message.contains(qsoDx)
        return when {
            isPartner -> PARTNER
            isCq -> CQ
            isToMe && !qsoActive -> TO_ME
            else -> ""
        }
    }
}
