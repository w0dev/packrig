package net.ft8vc.data

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationsTest {

    // 2026-07-01T23:59:30Z and 2026-07-02T00:00:30Z straddle a UTC day boundary.
    private val justBeforeMidnight = 1_782_950_370_000L
    private val justAfterMidnight = 1_782_950_430_000L

    private fun contact(dx: String, utc: Long, parks: String?) = QsoContact(
        utcMillis = utc,
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = dx,
        dxGrid = null,
        rstSent = -5,
        rstRcvd = -10,
        freqHz = 14_074_000L,
        band = "20m",
        potaParkRefs = parks,
    )

    @Test
    fun utcDateUsesUtcNotLocal() {
        assertEquals("20260701", Activations.utcDateOf(justBeforeMidnight))
        assertEquals("20260702", Activations.utcDateOf(justAfterMidnight))
    }

    @Test
    fun groupsByParkAndUtcDayExcludingHomeQsos() {
        val contacts = listOf(
            contact("K1ABC", justBeforeMidnight, "US-3315,US-0891"), // two-fer
            contact("K2DEF", justBeforeMidnight, "US-3315"),
            contact("K3GHI", justAfterMidnight, "US-3315"),          // next UTC day
            contact("K4JKL", justBeforeMidnight, null),              // home QSO
        )
        val activations = Activations.groupActivations(contacts)
        assertEquals(3, activations.size)
        assertTrue(activations.contains(Activation("US-3315", "20260701", 2)))
        assertTrue(activations.contains(Activation("US-0891", "20260701", 1)))
        assertTrue(activations.contains(Activation("US-3315", "20260702", 1)))
    }

    @Test
    fun contactsForSelectsExactlyTheActivationsQsos() {
        val twoFer = contact("K1ABC", justBeforeMidnight, "US-3315,US-0891")
        val other = contact("K3GHI", justAfterMidnight, "US-3315")
        val home = contact("K4JKL", justBeforeMidnight, null)
        val group = Activations.contactsFor(listOf(twoFer, other, home), "US-0891", "20260701")
        assertEquals(listOf(twoFer), group)
    }

    @Test
    fun fileNameSanitizesCallsign() {
        assertEquals(
            "W0DEV-P@US-3315-20260701.adi",
            Activations.fileName("W0DEV/P", "US-3315", "20260701"),
        )
        assertEquals(
            "W0DEV@US-3315-20260701.adi",
            Activations.fileName("w0dev", "US-3315", "20260701"),
        )
    }
}
