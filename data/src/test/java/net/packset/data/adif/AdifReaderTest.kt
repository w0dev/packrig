package net.packset.data.adif

import net.packset.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AdifReaderTest {

    private val contact = QsoContact(
        utcMillis = 1_700_000_000_000L, // 2023-11-14 22:13:20 UTC
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
    fun roundTripsAdifWriterOutput() {
        val adif = AdifWriter.export(listOf(contact, contact.copy(dxCall = "N5XYZ", utcMillis = 1_700_000_300_000L)))
        val result = AdifReader.read(adif)
        assertEquals(2, result.contacts.size)
        assertEquals(0, result.skipped)
        val parsed = result.contacts[0]
        assertEquals(contact.utcMillis / 1000, parsed.utcMillis / 1000) // second precision
        assertEquals("K1ABC", parsed.dxCall)
        assertEquals("FN42", parsed.dxGrid)
        assertEquals("W0DEV", parsed.myCall)
        assertEquals("EM26", parsed.myGrid)
        assertEquals(-8, parsed.rstSent)
        assertEquals(-15, parsed.rstRcvd)
        assertEquals(14_074_000L, parsed.freqHz)
        assertEquals("FT8", parsed.mode)
        assertEquals("20m", parsed.band)
    }

    @Test
    fun roundTripsPotaParkRefs() {
        val adif = AdifWriter.export(listOf(contact.copy(potaParkRefs = "US-3315")))
        val parsed = AdifReader.read(adif).contacts.single()
        assertEquals("US-3315", parsed.potaParkRefs)
    }

    @Test
    fun parsesLowercaseTagsAndMinutePrecisionTime() {
        val adif = "<call:5>K1ABC<qso_date:8>20231114<time_on:4>2213" +
            "<band:3>20m<mode:3>FT8<eor>"
        val parsed = AdifReader.read(adif, fallbackMyCall = "W0DEV", fallbackMyGrid = "EM26").contacts.single()
        assertEquals("K1ABC", parsed.dxCall)
        assertEquals("W0DEV", parsed.myCall) // fallback: file has no STATION_CALLSIGN
        assertEquals("EM26", parsed.myGrid)
        assertEquals("20m", parsed.band)
        // 2023-11-14 22:13:00 UTC
        assertEquals(1_699_999_980_000L, parsed.utcMillis)
    }

    @Test
    fun ignoresHeaderAndUnknownFields() {
        val adif = "<ADIF_VER:5>3.1.4<PROGRAMID:7>Packset<EOH>\n" +
            "<CALL:5>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:3>20m<QSL_RCVD:1>N<EOR>\n"
        val result = AdifReader.read(adif)
        assertEquals(1, result.contacts.size)
        assertEquals("K1ABC", result.contacts.single().dxCall)
    }

    @Test
    fun skipsRecordsMissingCallOrTime() {
        val adif = "<CALL:5>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:3>20m<EOR>" +
            "<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:3>20m<EOR>" + // no CALL
            "<CALL:5>N5XYZ<BAND:3>20m<EOR>" // no date/time
        val result = AdifReader.read(adif)
        assertEquals(1, result.contacts.size)
        assertEquals(2, result.skipped)
    }

    @Test
    fun invalidBandBecomesNull() {
        val adif = "<CALL:5>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:4>999m<FREQ:9>14.074000<EOR>"
        val parsed = AdifReader.read(adif).contacts.single()
        assertNull(parsed.band)
        assertEquals(14_074_000L, parsed.freqHz)
    }

    @Test
    fun garbageInputThrows() {
        assertThrows(AdifImportException::class.java) { AdifReader.read("not adif at all") }
    }

    @Test
    fun allRecordsUnreadableThrows() {
        assertThrows(AdifImportException::class.java) {
            AdifReader.read("<QSO_DATE:8>20231114<EOR>")
        }
    }
}
