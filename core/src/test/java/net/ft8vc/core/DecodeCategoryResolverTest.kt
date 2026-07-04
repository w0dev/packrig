package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixed-priority classification: OWN_TX > PARTNER > CQ variants > MY_CALL > OTHER.
 * See docs/superpowers/specs/2026-07-04-decode-colorscheme-design.md.
 */
class DecodeCategoryResolverTest {

    private fun resolve(
        isTx: Boolean = false,
        isCq: Boolean = false,
        isToMe: Boolean = false,
        workedBefore: WorkedBefore = WorkedBefore.Never,
        qsoActive: Boolean = false,
        qsoDx: String? = null,
        message: String = "K1ABC W1XYZ FN42",
    ): DecodeCategory = DecodeCategoryResolver.resolve(
        isTx = isTx,
        isCq = isCq,
        isToMe = isToMe,
        workedBefore = workedBefore,
        qsoActive = qsoActive,
        qsoDx = qsoDx,
        message = message,
    )

    @Test
    fun ownTxOutranksPartner_txMessageContainsPartnerCall() {
        // A transmitted row's text contains the partner call — must stay OWN_TX.
        val category = resolve(
            isTx = true,
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "K1ABC W0DEV R-07",
        )
        assertEquals(DecodeCategory.OWN_TX, category)
    }

    @Test
    fun partnerReplyMidQsoIsPartner_notMyCall() {
        // Partner reply contains my call too; PARTNER outranks MY_CALL (keeps ▸).
        val category = resolve(
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "W0DEV K1ABC -05",
        )
        assertEquals(DecodeCategory.PARTNER, category)
    }

    @Test
    fun partnerAnsweringAnotherStationIsStillPartner() {
        val category = resolve(
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "N5XYZ K1ABC -12",
        )
        assertEquals(DecodeCategory.PARTNER, category)
    }

    @Test
    fun tailEnderMidQsoIsMyCall() {
        // The original bug: a to-me message mid-QSO must classify strongly,
        // never blend into CQ/chatter styling.
        val category = resolve(
            isToMe = true,
            qsoActive = true,
            qsoDx = "K1ABC",
            message = "W0DEV N5XYZ EM10",
        )
        assertEquals(DecodeCategory.MY_CALL, category)
    }

    @Test
    fun toMeWhileIdleIsMyCall() {
        val category = resolve(isToMe = true, message = "W0DEV K1ABC FN42")
        assertEquals(DecodeCategory.MY_CALL, category)
    }

    @Test
    fun partnerRuleRequiresActiveQso() {
        // qsoDx set but QSO not active (e.g. stale form) — falls through.
        val category = resolve(
            qsoActive = false,
            qsoDx = "K1ABC",
            message = "N5XYZ K1ABC -12",
        )
        assertEquals(DecodeCategory.OTHER, category)
    }

    @Test
    fun cqFromNeverWorkedIsCqNew() {
        val category = resolve(isCq = true, message = "CQ K1ABC FN42")
        assertEquals(DecodeCategory.CQ_NEW, category)
    }

    @Test
    fun cqWorkedThisBand() {
        val category = resolve(
            isCq = true,
            workedBefore = WorkedBefore.ThisBand,
            message = "CQ K1ABC FN42",
        )
        assertEquals(DecodeCategory.CQ_WORKED_THIS_BAND, category)
    }

    @Test
    fun cqWorkedOtherBand() {
        val category = resolve(
            isCq = true,
            workedBefore = WorkedBefore.OtherBand,
            message = "CQ K1ABC FN42",
        )
        assertEquals(DecodeCategory.CQ_WORKED_OTHER_BAND, category)
    }

    @Test
    fun nonCqFromWorkedCallIsOther() {
        // Worked-before categories apply only to CQ rows (spec §1).
        val category = resolve(
            workedBefore = WorkedBefore.ThisBand,
            message = "K1ABC W1XYZ FN42",
        )
        assertEquals(DecodeCategory.OTHER, category)
    }

    @Test
    fun unrelatedChatterIsOther() {
        val category = resolve()
        assertEquals(DecodeCategory.OTHER, category)
    }
}
