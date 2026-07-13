package net.packrig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerSelectorTest {

    private val myCall = "W0DEV"
    private val myGrid = "EM26"

    @Test
    fun selectGridReply_firstUsesSlotOrder() {
        val decodes = listOf(
            QsoDecode("W0DEV K1ABC FN42", -10),
            QsoDecode("W0DEV N0XYZ FN20", -20),
        )
        val picked = AnswerSelector.selectGridReply(myCall, myGrid, decodes, AnswerPolicy.FIRST)
        assertEquals("W0DEV K1ABC FN42", picked?.message)
    }

    @Test
    fun selectGridReply_bestSnrPicksStrongest() {
        val decodes = listOf(
            QsoDecode("W0DEV K1ABC FN42", -10),
            QsoDecode("W0DEV N0XYZ FN20", -3),
        )
        val picked = AnswerSelector.selectGridReply(myCall, myGrid, decodes, AnswerPolicy.BEST_SNR)
        assertEquals("W0DEV N0XYZ FN20", picked?.message)
    }

    @Test
    fun selectGridReply_furthestPicksDistantGrid() {
        val decodes = listOf(
            QsoDecode("W0DEV K1ABC EM26", -5),
            QsoDecode("W0DEV N0XYZ FN42", -20),
        )
        val picked = AnswerSelector.selectGridReply(myCall, myGrid, decodes, AnswerPolicy.FURTHEST)
        assertEquals("W0DEV N0XYZ FN42", picked?.message)
    }

    @Test
    fun selectCq_skipsOwnCall() {
        assertNull(
            AnswerSelector.selectCq(
                myCall,
                myGrid,
                listOf(QsoDecode("CQ W0DEV EM26", -5)),
                AnswerPolicy.FIRST,
            ),
        )
    }

    @Test
    fun selectCq_bestSnrAmongCqs() {
        val picked = AnswerSelector.selectCq(
            myCall,
            myGrid,
            listOf(
                QsoDecode("CQ K1ABC FN42", -12),
                QsoDecode("CQ N0XYZ FN20", -4),
            ),
            AnswerPolicy.BEST_SNR,
        )
        assertEquals("CQ N0XYZ FN20", picked?.message)
    }

    @Test
    fun selectOpportunity_bestSnrWhenMultipleDirected() {
        val opp = AnswerSelector.selectOpportunity(
            myCall,
            myGrid,
            listOf(
                QsoDecode("W0DEV K1ABC FN42", -15),
                QsoDecode("W0DEV N0XYZ FN20", -6),
            ),
            AnswerPolicy.BEST_SNR,
        )
        requireNotNull(opp)
        assertEquals("N0XYZ", opp.dxCall)
    }

    @Test
    fun selectOpportunity_skipsAbandonedPartner() {
        val opp = AnswerSelector.selectOpportunity(
            myCall,
            myGrid,
            listOf(QsoDecode("W0DEV K1ABC FN42", -8)),
            AnswerPolicy.FIRST,
            excludedDx = setOf("K1ABC"),
        )
        assertNull(opp)
    }
}
