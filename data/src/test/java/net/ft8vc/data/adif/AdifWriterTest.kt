package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertTrue
import org.junit.Test

class AdifWriterTest {

    @Test
    fun exportsHeaderAndRecordFields() {
        val contact = QsoContact(
            utcMillis = 1_700_000_000_000L,
            myCall = "W0DEV",
            myGrid = "EM26",
            dxCall = "K1ABC",
            dxGrid = "FN42",
            rstSent = -8,
            rstRcvd = -15,
            freqHz = 14_074_000L,
            mode = "FT8",
            band = "20m",
        )
        val adif = AdifWriter.export(listOf(contact))
        assertTrue(adif.contains("<EOH>"))
        assertTrue(adif.contains("<CALL:5>K1ABC"))
        assertTrue(adif.contains("<MODE:3>FT8"))
        assertTrue(adif.contains("<MY_GRIDSQUARE:4>EM26"))
        assertTrue(adif.contains("<GRIDSQUARE:4>FN42"))
        assertTrue(adif.contains("<RST_SENT:2>-8"))
        assertTrue(adif.contains("<RST_RCVD:3>-15"))
        assertTrue(adif.contains("<EOR>"))
    }
}
