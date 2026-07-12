package net.packset.app.settings

import net.packset.core.DecodeCategory

/**
 * User-configurable ARGB colors for decode-row categories. One field per
 * configurable [DecodeCategory]; [DecodeCategory.OTHER] is intentionally not
 * configurable and always renders with the theme default.
 *
 * Defaults follow WSJT-X conventions: red for anything carrying my call,
 * amber for own TX, green for new CQs.
 */
data class DecodeColorScheme(
    val ownTx: Int = DEFAULT_OWN_TX,
    val partner: Int = DEFAULT_PARTNER,
    val myCall: Int = DEFAULT_MY_CALL,
    val cqNew: Int = DEFAULT_CQ_NEW,
    val cqWorkedOtherBand: Int = DEFAULT_CQ_WORKED_OTHER_BAND,
    val cqWorkedThisBand: Int = DEFAULT_CQ_WORKED_THIS_BAND,
) {

    fun colorFor(category: DecodeCategory): Int? = when (category) {
        DecodeCategory.OWN_TX -> ownTx
        DecodeCategory.PARTNER -> partner
        DecodeCategory.MY_CALL -> myCall
        DecodeCategory.CQ_NEW -> cqNew
        DecodeCategory.CQ_WORKED_OTHER_BAND -> cqWorkedOtherBand
        DecodeCategory.CQ_WORKED_THIS_BAND -> cqWorkedThisBand
        DecodeCategory.OTHER -> null
    }

    fun withColor(category: DecodeCategory, argb: Int): DecodeColorScheme = when (category) {
        DecodeCategory.OWN_TX -> copy(ownTx = argb)
        DecodeCategory.PARTNER -> copy(partner = argb)
        DecodeCategory.MY_CALL -> copy(myCall = argb)
        DecodeCategory.CQ_NEW -> copy(cqNew = argb)
        DecodeCategory.CQ_WORKED_OTHER_BAND -> copy(cqWorkedOtherBand = argb)
        DecodeCategory.CQ_WORKED_THIS_BAND -> copy(cqWorkedThisBand = argb)
        DecodeCategory.OTHER -> this
    }

    companion object {
        val DEFAULT_OWN_TX = 0xFFFFB347.toInt()               // Ft8Amber
        val DEFAULT_PARTNER = 0xFFE63946.toInt()              // Ft8Red
        val DEFAULT_MY_CALL = 0xFFE63946.toInt()              // Ft8Red
        val DEFAULT_CQ_NEW = 0xFF3DDC97.toInt()               // Ft8Green
        val DEFAULT_CQ_WORKED_OTHER_BAND = 0xFF4CC9F0.toInt() // cyan
        val DEFAULT_CQ_WORKED_THIS_BAND = 0xFF9AA0A6.toInt()  // muted gray

        val DEFAULT = DecodeColorScheme()
    }
}
