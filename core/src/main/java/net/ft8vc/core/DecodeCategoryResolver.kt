package net.ft8vc.core

/**
 * Classifies a decode row into a [DecodeCategory] with fixed priority:
 * OWN_TX > PARTNER > CQ variants > MY_CALL > OTHER.
 *
 * OWN_TX must be checked first: a transmitted row's message text contains
 * both the partner call and my call, so it would otherwise match PARTNER.
 * PARTNER outranks MY_CALL because partner replies also contain my call.
 * CQ outranks MY_CALL as a defensive rule for inconsistently flagged rows
 * (unreachable in the current pipeline — isDirectedToMe never matches CQs):
 * a broadcast must never render as "calling you". Pinned by the v1.0 test
 * DecodePrefixTest.cqPrefixWinsOverToMe.
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
        isCq && workedBefore == WorkedBefore.ThisBand -> DecodeCategory.CQ_WORKED_THIS_BAND
        isCq && workedBefore == WorkedBefore.OtherBand -> DecodeCategory.CQ_WORKED_OTHER_BAND
        isCq -> DecodeCategory.CQ_NEW
        isToMe -> DecodeCategory.MY_CALL
        else -> DecodeCategory.OTHER
    }
}
