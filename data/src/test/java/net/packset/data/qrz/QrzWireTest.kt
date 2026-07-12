package net.packset.data.qrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrzWireTest {

    @Test
    fun parse_okStatusResponse() {
        val fields = QrzWire.parse("RESULT=OK&CALLSIGN=W0DEV&COUNT=42")
        assertEquals("OK", fields["RESULT"])
        assertEquals("W0DEV", fields["CALLSIGN"])
        assertEquals("42", fields["COUNT"])
    }

    @Test
    fun parse_failWithSpacesAndEscapes() {
        val fields = QrzWire.parse("RESULT=FAIL&REASON=Unable%20to%20add%20QSO")
        assertEquals("FAIL", fields["RESULT"])
        assertEquals("Unable to add QSO", fields["REASON"])
    }

    @Test
    fun parse_literalSpacesSurvive() {
        val fields = QrzWire.parse("RESULT=AUTH&REASON=invalid api key")
        assertEquals("invalid api key", fields["REASON"])
    }

    @Test
    fun parse_lowercaseKeysNormalized() {
        assertEquals("OK", QrzWire.parse("result=OK")["RESULT"])
    }

    @Test
    fun parse_malformedPiecesIgnored() {
        val fields = QrzWire.parse("garbage&RESULT=OK&=nokey&novalue=")
        assertEquals("OK", fields["RESULT"])
        assertEquals("", fields["NOVALUE"])
        assertFalse(fields.containsKey("GARBAGE"))
    }

    @Test
    fun parse_htmlErrorPageYieldsNoResult() {
        val fields = QrzWire.parse("<html><body>502 Bad Gateway</body></html>")
        assertFalse(fields.containsKey("RESULT"))
    }

    @Test
    fun encodeForm_escapesAdifPayload() {
        val body = QrzWire.encodeForm(
            linkedMapOf(
                "KEY" to "ABCD-1234",
                "ACTION" to "INSERT",
                "ADIF" to "<call:5>K1ABC <eor>",
            ),
        )
        assertEquals("KEY=ABCD-1234&ACTION=INSERT&ADIF=%3Ccall%3A5%3EK1ABC+%3Ceor%3E", body)
    }

    @Test
    fun isDuplicateReason_matchesQrzDuplicateText() {
        assertTrue(QrzWire.isDuplicateReason("Unable to add QSO to database: duplicate"))
        assertTrue(QrzWire.isDuplicateReason("DUPLICATE entry"))
        assertFalse(QrzWire.isDuplicateReason("invalid api key"))
        assertFalse(QrzWire.isDuplicateReason(null))
    }
}
