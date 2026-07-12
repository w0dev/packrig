package net.packset.data.adif

import org.junit.Assert.assertThrows
import org.junit.Test

class AdifValidatorTest {

    @Test
    fun acceptsValidRecord() {
        val adif = """
            <ADIF_VER:5>3.1.4<PROGRAMID:7>Packset<EOH>

            <QSO_DATE:8>20231114<TIME_ON:6>022800<CALL:5>K1ABC<MODE:3>FT8<BAND:3>20m<EOR>
        """.trimIndent()
        AdifValidator.validateExport(adif)
    }

    @Test
    fun rejectsLengthMismatch() {
        val adif = """
            <ADIF_VER:5>3.1.4<EOH>

            <CALL:6>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>022800<MODE:3>FT8<BAND:3>20m<EOR>
        """.trimIndent()
        assertThrows(AdifExportException::class.java) {
            AdifValidator.validateExport(adif)
        }
    }

    @Test
    fun rejectsSubmodeForFt8() {
        val adif = """
            <ADIF_VER:5>3.1.4<EOH>

            <QSO_DATE:8>20231114<TIME_ON:6>022800<CALL:5>K1ABC<MODE:3>FT8<SUBMODE:3>FT8<BAND:3>20m<EOR>
        """.trimIndent()
        assertThrows(AdifExportException::class.java) {
            AdifValidator.validateExport(adif)
        }
    }
}
