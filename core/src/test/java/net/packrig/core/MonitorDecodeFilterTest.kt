package net.packrig.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorDecodeFilterTest {

    private val myCall = "W0DEV"
    private val txTone = 1000

    @Test
    fun isCqOrSignOff_matchesCqAndSignOffs() {
        assertTrue(MonitorDecodeFilter.isCqOrSignOff("CQ W0DEV EM26"))
        assertTrue(MonitorDecodeFilter.isCqOrSignOff("CQ POTA W0DEV EM26"))
        assertTrue(MonitorDecodeFilter.isCqOrSignOff("W0DEV K1ABC 73"))
        assertTrue(MonitorDecodeFilter.isCqOrSignOff("W0DEV K1ABC RR73"))
    }

    @Test
    fun isCqOrSignOff_rejectsMidQsoTraffic() {
        assertFalse(MonitorDecodeFilter.isCqOrSignOff("W0DEV K1ABC FN42"))
        assertFalse(MonitorDecodeFilter.isCqOrSignOff("W0DEV K1ABC -07"))
        assertFalse(MonitorDecodeFilter.isCqOrSignOff("W0DEV K1ABC RRR"))
        assertFalse(MonitorDecodeFilter.isCqOrSignOff("random text here"))
    }

    @Test
    fun messageInvolvesMyCall_matchesToAndFrom() {
        assertTrue(MonitorDecodeFilter.messageInvolvesMyCall("K1ABC W0DEV FN42", myCall))
        assertTrue(MonitorDecodeFilter.messageInvolvesMyCall("W0DEV K1ABC -05", myCall))
        assertTrue(MonitorDecodeFilter.messageInvolvesMyCall("K1ABC W0DEV/P FN42", myCall))
        assertFalse(MonitorDecodeFilter.messageInvolvesMyCall("CQ W0DEV EM26", myCall))
        assertFalse(MonitorDecodeFilter.messageInvolvesMyCall("N0XYZ W1ABC FN42", myCall))
    }

    @Test
    fun nearTxTone_withinWindow() {
        assertTrue(MonitorDecodeFilter.nearTxTone(1000, txTone))
        assertTrue(MonitorDecodeFilter.nearTxTone(1150, txTone))
        assertTrue(MonitorDecodeFilter.nearTxTone(850, txTone))
        assertFalse(MonitorDecodeFilter.nearTxTone(1160, txTone))
    }

    @Test
    fun visible_whenFilterOff_showsEverything() {
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "W0DEV K1ABC FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.ALL,
                cq73OnlyFilter = false,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }

    @Test
    fun visible_whenFilterOn_keepsCqAndSignOff() {
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "CQ N0XYZ EN50",
                isCq = true,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.ALL,
                cq73OnlyFilter = true,
                qsoDx = null,
                qsoActive = false,
            ),
        )
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "W0DEV K1ABC 73",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.ALL,
                cq73OnlyFilter = true,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }

    @Test
    fun visible_whenFilterOn_hidesUnrelatedMidQso() {
        assertFalse(
            MonitorDecodeFilter.visibleForDisplay(
                message = "W0DEV K1ABC FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.ALL,
                cq73OnlyFilter = true,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }

    @Test
    fun visible_whenFilterOnAndQsoActive_keepsPartnerTraffic() {
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "K1ABC W0DEV -05",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.ALL,
                cq73OnlyFilter = true,
                qsoDx = "K1ABC",
                qsoActive = true,
            ),
        )
        assertFalse(
            MonitorDecodeFilter.visibleForDisplay(
                message = "N0XYZ W0DEV FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.ALL,
                cq73OnlyFilter = true,
                qsoDx = "K1ABC",
                qsoActive = true,
            ),
        )
    }

    @Test
    fun operateMode_showsCqMyCallTrafficPartnerAndTxTone() {
        assertTrue(
            MonitorDecodeFilter.visibleInOperateMode(
                message = "CQ N0XYZ EN50",
                isCq = true,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                qsoDx = null,
                qsoActive = false,
            ),
        )
        assertTrue(
            MonitorDecodeFilter.visibleInOperateMode(
                message = "K1ABC W0DEV FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                qsoDx = null,
                qsoActive = false,
            ),
        )
        assertTrue(
            MonitorDecodeFilter.visibleInOperateMode(
                message = "K1ABC W0DEV -05",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                qsoDx = "K1ABC",
                qsoActive = true,
            ),
        )
        assertTrue(
            MonitorDecodeFilter.visibleInOperateMode(
                message = "N0XYZ W1ABC FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 1050,
                txToneHz = txTone,
                qsoDx = null,
                qsoActive = false,
            ),
        )
        assertFalse(
            MonitorDecodeFilter.visibleInOperateMode(
                message = "N0XYZ W1ABC FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }

    @Test
    fun operateMode_showsMultipleAnswersWhenCallingCq() {
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "K1ABC W0DEV FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.OPERATE,
                cq73OnlyFilter = false,
                qsoDx = null,
                qsoActive = false,
            ),
        )
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "N0XYZ W0DEV EM50",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.OPERATE,
                cq73OnlyFilter = false,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }

    @Test
    fun operateMode_ignoresCq73Filter() {
        assertTrue(
            MonitorDecodeFilter.visibleForDisplay(
                message = "K1ABC W0DEV FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.OPERATE,
                cq73OnlyFilter = true,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }

    @Test
    fun operateMode_hidesUnrelatedFarFromTxTone() {
        assertFalse(
            MonitorDecodeFilter.visibleForDisplay(
                message = "N0XYZ W1ABC FN42",
                isCq = false,
                myCall = myCall,
                freqHz = 2000,
                txToneHz = txTone,
                viewMode = DecodeViewMode.OPERATE,
                cq73OnlyFilter = false,
                qsoDx = null,
                qsoActive = false,
            ),
        )
    }
}
