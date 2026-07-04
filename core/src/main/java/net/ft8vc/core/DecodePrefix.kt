package net.ft8vc.core

/**
 * Decorates decode messages with a single non-color glyph so the row type is
 * visible to operators who cannot rely on color (color-blindness, dim screens).
 *
 * - `●` — CQ (any worked-before state)
 * - `→` — directed to my callsign (in or out of a QSO)
 * - `▸` — current QSO partner during an active QSO
 * - blank — own TX rows and every other decode
 *
 * Glyphs derive from [DecodeCategoryResolver] so prefix and row color can
 * never disagree. Pure function so the same logic can be reused in tests
 * and previews.
 */
object DecodePrefix {

    const val CQ = "● "
    const val TO_ME = "→ "
    const val PARTNER = "▸ "

    fun glyphFor(category: DecodeCategory): String = when (category) {
        DecodeCategory.PARTNER -> PARTNER
        DecodeCategory.MY_CALL -> TO_ME
        DecodeCategory.CQ_NEW,
        DecodeCategory.CQ_WORKED_OTHER_BAND,
        DecodeCategory.CQ_WORKED_THIS_BAND -> CQ
        DecodeCategory.OWN_TX, DecodeCategory.OTHER -> ""
    }

    /**
     * Kept for compatibility and as the pin for the legacy DecodePrefixTest
     * expectations (including cqPrefixWinsOverToMe); production rendering calls
     * [glyphFor] directly.
     */
    fun prefixFor(
        message: String,
        isCq: Boolean,
        isToMe: Boolean,
        qsoActive: Boolean,
        qsoDx: String?,
    ): String = glyphFor(
        DecodeCategoryResolver.resolve(
            isTx = false,
            isCq = isCq,
            isToMe = isToMe,
            workedBefore = WorkedBefore.Never,
            qsoActive = qsoActive,
            qsoDx = qsoDx,
            message = message,
        ),
    )
}
