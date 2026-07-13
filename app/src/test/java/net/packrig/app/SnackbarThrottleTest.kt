package net.packrig.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-07-03 field report: a repeating TX-preflight error queued identical
 * 10-second snackbars back to back, loitering for over a minute. The throttle
 * drops repeats of the message shown most recently within the window.
 */
class SnackbarThrottleTest {

    private var nowMs = 0L
    private val throttle = SnackbarThrottle(windowMs = 10_000L, clock = { nowMs })

    @Test
    fun `first event is shown`() {
        assertTrue(throttle.shouldShow("Digirig disconnected — RX only"))
    }

    @Test
    fun `identical text inside the window is suppressed`() {
        assertTrue(throttle.shouldShow("Reconnect Digirig"))
        nowMs = 9_999L
        assertFalse(throttle.shouldShow("Reconnect Digirig"))
    }

    @Test
    fun `identical text after the window is shown again`() {
        assertTrue(throttle.shouldShow("Reconnect Digirig"))
        nowMs = 10_000L
        assertTrue(throttle.shouldShow("Reconnect Digirig"))
    }

    @Test
    fun `different text is shown immediately`() {
        assertTrue(throttle.shouldShow("Reconnect Digirig"))
        nowMs = 100L
        assertTrue(throttle.shouldShow("QSO logged: K1ABC"))
    }

    @Test
    fun `showing a different text rearms the previous one`() {
        assertTrue(throttle.shouldShow("A"))
        nowMs = 100L
        assertTrue(throttle.shouldShow("B"))
        nowMs = 200L
        assertTrue("only the most recently shown text is throttled", throttle.shouldShow("A"))
    }

    @Test
    fun `window restarts from the last shown occurrence, not from suppressed repeats`() {
        assertTrue(throttle.shouldShow("A"))
        nowMs = 9_000L
        assertFalse(throttle.shouldShow("A"))
        nowMs = 10_000L
        assertTrue("suppressed repeat must not extend the window", throttle.shouldShow("A"))
    }
}
