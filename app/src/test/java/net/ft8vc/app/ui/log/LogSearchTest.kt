package net.ft8vc.app.ui.log

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSearchTest {

    private fun contact(id: Long, dxCall: String): QsoContact =
        QsoContact(
            id = id,
            utcMillis = 1_700_000_000_000L + id,
            myCall = "W0DEV",
            myGrid = "EN34",
            dxCall = dxCall,
            dxGrid = null,
            rstSent = -10,
            rstRcvd = -12,
            freqHz = 14_074_000L,
            band = "20m",
        )

    private val log = listOf(
        contact(1, "K7ABC"),
        contact(2, "JA1XYZ"),
        contact(3, "W7AB"),
    )

    @Test
    fun blankQueryReturnsAllContacts() {
        assertEquals(log, filterByCall(log, ""))
        assertEquals(log, filterByCall(log, "   "))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals(listOf(log[0]), filterByCall(log, "k7"))
    }

    @Test
    fun matchesSubstringAnywhereInCall() {
        assertEquals(listOf(log[0], log[2]), filterByCall(log, "7AB"))
    }

    @Test
    fun noMatchesReturnsEmptyList() {
        assertTrue(filterByCall(log, "VK9").isEmpty())
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        assertEquals(listOf(log[1]), filterByCall(log, "  ja1 "))
    }
}
