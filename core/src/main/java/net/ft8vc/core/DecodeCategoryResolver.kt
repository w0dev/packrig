package net.ft8vc.core

/**
 * Classifies a decode row into a [DecodeCategory] with fixed priority:
 * OWN_TX > PARTNER > MY_CALL > CQ variants > OTHER.
 *
 * OWN_TX must be checked first: a transmitted row's message text contains
 * both the partner call and my call, so it would otherwise match PARTNER.
 * PARTNER outranks MY_CALL because partner replies also contain my call.
 * Worked-before categories apply only to CQ rows — the decision they serve
 * is "should I answer this CQ?".
 */
object DecodeCategoryResolver {

    fun resolve(
        isTx: Boolean,
        isCq: Boolean,
        isToMe: Boolean,
        workedBefore: WorkedBefore,
        qsoActive: Boolean,
        qsoDx: String?,
        message: String,
    ): DecodeCategory = when {
        isTx -> DecodeCategory.OWN_TX
        qsoActive && qsoDx != null && message.contains(qsoDx) -> DecodeCategory.PARTNER
        isToMe -> DecodeCategory.MY_CALL
        isCq && workedBefore == WorkedBefore.ThisBand -> DecodeCategory.CQ_WORKED_THIS_BAND
        isCq && workedBefore == WorkedBefore.OtherBand -> DecodeCategory.CQ_WORKED_OTHER_BAND
        isCq -> DecodeCategory.CQ_NEW
        else -> DecodeCategory.OTHER
    }
}
