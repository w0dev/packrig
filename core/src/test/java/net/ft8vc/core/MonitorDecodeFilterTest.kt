package net.ft8vc.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorDecodeFilterTest {

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
    fun visible_whenFilterOff_showsEverything() {
        assertTrue(MonitorDecodeFilter.visible("W0DEV K1ABC FN42", filterEnabled = false, qsoDx = null, qsoActive = false))
    }

    @Test
    fun visible_whenFilterOn_keepsCqAndSignOff() {
        assertTrue(MonitorDecodeFilter.visible("CQ N0XYZ EN50", filterEnabled = true, qsoDx = null, qsoActive = false))
        assertTrue(MonitorDecodeFilter.visible("W0DEV K1ABC 73", filterEnabled = true, qsoDx = null, qsoActive = false))
    }

    @Test
    fun visible_whenFilterOn_hidesUnrelatedMidQso() {
        assertFalse(MonitorDecodeFilter.visible("W0DEV K1ABC FN42", filterEnabled = true, qsoDx = null, qsoActive = false))
    }

    @Test
    fun visible_whenFilterOnAndQsoActive_keepsPartnerTraffic() {
        assertTrue(
            MonitorDecodeFilter.visible(
                "K1ABC W0DEV -05",
                filterEnabled = true,
                qsoDx = "K1ABC",
                qsoActive = true,
            ),
        )
        assertFalse(
            MonitorDecodeFilter.visible(
                "N0XYZ W0DEV FN42",
                filterEnabled = true,
                qsoDx = "K1ABC",
                qsoActive = true,
            ),
        )
    }
}
