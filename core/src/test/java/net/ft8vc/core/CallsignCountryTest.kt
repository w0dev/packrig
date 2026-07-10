package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignCountryTest {

    @Test
    fun `plain prefixes resolve to iso codes`() {
        assertEquals("US", CallsignCountry.isoFor("K1ABC"))
        assertEquals("JP", CallsignCountry.isoFor("JA1XYZ"))
        assertEquals("DE", CallsignCountry.isoFor("DL1ABC"))
        assertEquals("GB", CallsignCountry.isoFor("G4ABC"))
        assertEquals("BR", CallsignCountry.isoFor("PY2XYZ"))
    }

    @Test
    fun `ham-only dxcc distinctions collapse to iso`() {
        assertEquals("US", CallsignCountry.isoFor("KL7AA"))   // Alaska
        assertEquals("US", CallsignCountry.isoFor("KH6AA"))   // Hawaii
        assertEquals("RU", CallsignCountry.isoFor("RA9AA"))   // Asiatic Russia
        assertEquals("IT", CallsignCountry.isoFor("IS0ABC"))  // Sardinia
        assertEquals("FK", CallsignCountry.isoFor("VP8ABC"))  // Falklands default
    }

    @Test
    fun `portable designators and suffixes`() {
        assertEquals("FR", CallsignCountry.isoFor("F/DL1ABC"))
        assertEquals("DE", CallsignCountry.isoFor("DL1ABC/P"))
        assertEquals("US", CallsignCountry.isoFor("W1ABC/7"))
        assertNull(CallsignCountry.isoFor("W1ABC/MM"))
    }

    @Test
    fun `unresolvable calls return null`() {
        assertNull(CallsignCountry.isoFor("<PJ4/K1ABC>"))
        assertNull(CallsignCountry.isoFor(""))
        assertNull(CallsignCountry.isoFor("QAA1AA")) // Q block unallocated
    }

    @Test
    fun `lookup is case and whitespace tolerant`() {
        assertEquals("JP", CallsignCountry.isoFor(" ja1xyz "))
    }

    @Test
    fun `generated table is well formed`() {
        val prefixEntries = CallsignCountryTable.PREFIX_ENTRIES
            .flatMap { it.split(';') }.filter { it.isNotEmpty() }
        val exactEntries = CallsignCountryTable.EXACT_ENTRIES
            .flatMap { it.split(';') }.filter { it.isNotEmpty() }
        assertTrue("table must not be empty", prefixEntries.size > 300)
        var maxPrefixLen = 0
        for (e in prefixEntries + exactEntries) {
            val parts = e.split('=')
            assertEquals("entry '$e' must be KEY=CC", 2, parts.size)
            assertTrue(
                "code '${parts[1]}' in '$e' must be 2 uppercase letters",
                parts[1].matches(Regex("[A-Z]{2}")),
            )
        }
        for (e in prefixEntries) {
            maxPrefixLen = maxOf(maxPrefixLen, e.substringBefore('=').length)
        }
        assertEquals(CallsignCountryTable.MAX_PREFIX_LEN, maxPrefixLen)
    }
}
