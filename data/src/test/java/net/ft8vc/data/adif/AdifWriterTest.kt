package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdifWriterTest {

    private val contact = QsoContact(
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

    @Test
    fun exportsValidatedHeaderAndRecordFields() {
        val adif = AdifWriter.export(listOf(contact))
        assertTrue(adif.contains("<ADIF_VER:5>3.1.4"))
        assertTrue(adif.contains("<PROGRAMID:5>FT8VC"))
        assertTrue(adif.contains("<EOH>"))
        assertTrue(adif.contains("<CALL:5>K1ABC"))
        assertTrue(adif.contains("<MODE:3>FT8"))
        assertFalse(adif.contains("SUBMODE"))
        assertTrue(adif.contains("<MY_GRIDSQUARE:4>EM26"))
        assertTrue(adif.contains("<GRIDSQUARE:4>FN42"))
        assertTrue(adif.contains("<RST_SENT:3>-08"))
        assertTrue(adif.contains("<RST_RCVD:3>-15"))
        assertTrue(adif.contains("<BAND:3>20m"))
        assertTrue(adif.contains("<EOR>"))
    }

    @Test
    fun exportsPotaFieldsWhenEnabled() {
        val adif = AdifWriter.export(
            listOf(contact),
            AdifExportContext(potaEnabled = true, potaParkRef = "US-3315"),
        )
        assertTrue(adif.contains("<MY_SIG:4>POTA"))
        assertTrue(adif.contains("<MY_SIG_INFO:7>US-3315"))
    }

    @Test(expected = AdifExportException::class)
    fun rejectsPotaExportWithoutParkRef() {
        AdifWriter.export(listOf(contact), AdifExportContext(potaEnabled = true, potaParkRef = null))
    }

    @Test(expected = AdifExportException::class)
    fun rejectsEmptyExport() {
        AdifWriter.export(emptyList())
    }
}
