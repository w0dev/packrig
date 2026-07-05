package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Test

class AdifImportMergeTest {

    private fun qso(call: String, utc: Long, band: String? = "20m") = QsoContact(
        utcMillis = utc, myCall = "W0DEV", myGrid = "EM26",
        dxCall = call, dxGrid = null, rstSent = null, rstRcvd = null,
        freqHz = null, band = band,
    )

    @Test
    fun insertsNewContactsAndCountsDuplicates() {
        val existing = listOf(qso("K1ABC", 1_000_000L))
        val incoming = listOf(qso("K1ABC", 1_000_000L), qso("N5XYZ", 2_000_000L))
        val (toInsert, duplicates) = AdifImportMerge.partition(existing, incoming)
        assertEquals(listOf("N5XYZ"), toInsert.map { it.dxCall })
        assertEquals(1, duplicates)
    }

    @Test
    fun duplicateWindowBoundaries() {
        val existing = listOf(qso("K1ABC", 1_000_000L))
        // 89 s away: duplicate. 91 s away: distinct.
        val (toInsert, duplicates) = AdifImportMerge.partition(
            existing,
            listOf(qso("K1ABC", 1_000_000L + 89_000L), qso("K1ABC", 1_000_000L + 91_000L)),
        )
        assertEquals(1, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun callMatchIsCaseInsensitiveAndBandMatters() {
        val existing = listOf(qso("K1ABC", 1_000_000L, band = "20m"))
        val (toInsert, duplicates) = AdifImportMerge.partition(
            existing,
            listOf(
                qso("k1abc", 1_000_000L, band = "20m"), // dup, case-insensitive
                qso("K1ABC", 1_000_000L, band = "40m"), // different band → distinct
                qso("K1ABC", 1_000_000L, band = null),  // null band ≠ "20m" → distinct
            ),
        )
        assertEquals(2, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun fileInternalDuplicatesCollapse() {
        val (toInsert, duplicates) = AdifImportMerge.partition(
            emptyList(),
            listOf(qso("K1ABC", 1_000_000L), qso("K1ABC", 1_000_000L)),
        )
        assertEquals(1, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun reimportOfOwnExportIsFullNoOp() {
        val existing = (0 until 5).map { qso("K${it}ABC", it * 600_000L) }
        val (toInsert, duplicates) = AdifImportMerge.partition(existing, existing)
        assertEquals(0, toInsert.size)
        assertEquals(5, duplicates)
    }
}
