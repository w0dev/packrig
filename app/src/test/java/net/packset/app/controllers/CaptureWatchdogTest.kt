package net.packset.app.controllers

import net.packset.app.controllers.CaptureWatchdog.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureWatchdogTest {

    private var now = 100_000L
    // Custom (small) timings make the state transitions easy to drive deterministically.
    private fun watchdog(stall: Long = 1_000L, grace: Long = 0L, maxRestarts: Int = 3) =
        CaptureWatchdog(stall, grace, maxRestarts, clock = { now })

    @Test fun `recent frame is idle`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 500
        w.onFrame()
        assertEquals(Decision.Idle, w.poll(isCapturing = true, isTransmitting = false, devicePresent = true))
    }

    @Test fun `stall past threshold recovers`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 1_500
        assertEquals(Decision.Recover, w.poll(true, false, true))
    }

    @Test fun `within restart grace is idle even if stalled`() {
        val w = watchdog(stall = 1_000L, grace = 3_000L)
        w.onCaptureStarted()          // grace window: now .. now+3000
        now += 1_500                  // gap 1500 >= stall(1000) but still inside grace
        assertEquals(Decision.Idle, w.poll(true, false, true))
        now += 2_000                  // now past grace, still stalled
        assertEquals(Decision.Recover, w.poll(true, false, true))
    }

    @Test fun `maxRestarts consecutive stalls give up`() {
        val w = watchdog(maxRestarts = 3)
        repeat(3) {
            w.onCaptureStarted()      // simulate the restart the VM would perform
            now += 1_500
            assertEquals(Decision.Recover, w.poll(true, false, true))
        }
        w.onCaptureStarted()
        now += 1_500
        assertEquals(Decision.GiveUp, w.poll(true, false, true))
    }

    @Test fun `healthy interval past grace resets the restart counter`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 1_500
        assertEquals(Decision.Recover, w.poll(true, false, true))   // count -> 1
        // recovery succeeds: fresh capture + a frame
        w.onCaptureStarted()
        w.onFrame()
        now += 500
        assertEquals(Decision.Idle, w.poll(true, false, true))       // healthy -> count reset to 0
        // a later isolated stall must Recover again, not GiveUp
        now += 2_000
        assertEquals(Decision.Recover, w.poll(true, false, true))
    }

    @Test fun `not capturing suppresses recover`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000
        assertEquals(Decision.Idle, w.poll(isCapturing = false, isTransmitting = false, devicePresent = true))
    }

    @Test fun `transmitting suppresses recover`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000
        assertEquals(Decision.Idle, w.poll(true, isTransmitting = true, devicePresent = true))
    }

    @Test fun `absent device suppresses recover`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000
        assertEquals(Decision.Idle, w.poll(true, false, devicePresent = false))
    }

    @Test fun `tx gap then resume is not a stall`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000                  // long gap, but it's a TX pause:
        assertEquals(Decision.Idle, w.poll(isCapturing = false, isTransmitting = true, devicePresent = true))
        w.onCaptureStarted()          // TX ends, capture resumes -> VM re-stamps
        now += 500
        assertEquals(Decision.Idle, w.poll(true, false, true))
    }
}
