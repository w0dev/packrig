package net.packrig.app

import net.packrig.rig.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeResultTextTest {
    @Test
    fun everyOutcomeHasPlainLanguageCopy() {
        assertEquals("Sync OK — rig reports 14.074 MHz", probeResultText(ProbeResult.Sync(14_074_000L)))
        assertEquals(
            "Received data but couldn't understand it — likely a wrong baud rate",
            probeResultText(ProbeResult.Garbage),
        )
        assertEquals(
            "No response — check the baud rate, CAT port, cable, and the rig's CAT menu",
            probeResultText(ProbeResult.Silence),
        )
        assertEquals("No USB serial device attached", probeResultText(ProbeResult.NoDevice))
        assertEquals("USB permission not granted — connect the rig and allow access", probeResultText(ProbeResult.NoPermission))
        assertEquals("This rig setup has no CAT to test", probeResultText(ProbeResult.NoCat))
    }

    @Test
    fun echoOnly_pointsAtAddressAndPower() {
        val text = probeResultText(ProbeResult.EchoOnly)
        assertTrue(text.contains("CI-V address"))
    }
}
