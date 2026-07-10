package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockCorrectionTest {

    @Test fun `now equals raw clock when no offset applied`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        assertEquals(1_000_000L, cc.now())
        assertEquals(0L, cc.appliedOffsetMs)
    }

    @Test fun `applying positive residual rolls now back (fast-clock correction)`() {
        var raw = 1_000_000L
        val cc = ClockCorrection(rawClock = { raw })
        cc.applyResidualSeconds(1.3f)          // fast clock, per ClockOffsetEstimator sign
        assertEquals(1300L, cc.appliedOffsetMs)
        assertEquals(998_700L, cc.now())        // raw - 1300
    }

    @Test fun `applying negative residual rolls now forward (slow-clock correction)`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        cc.applyResidualSeconds(-0.5f)
        assertEquals(-500L, cc.appliedOffsetMs)
        assertEquals(1_000_500L, cc.now())
    }

    @Test fun `residuals accumulate so repeated applies converge`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        cc.applyResidualSeconds(1.0f)
        cc.applyResidualSeconds(0.2f)
        assertEquals(1200L, cc.appliedOffsetMs)
    }

    @Test fun `reset returns to zero offset`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        cc.applyResidualSeconds(2.0f)
        cc.reset()
        assertEquals(0L, cc.appliedOffsetMs)
        assertEquals(1_000_000L, cc.now())
    }

    @Test fun `rounding is to nearest millisecond`() {
        val cc = ClockCorrection(rawClock = { 0L })
        cc.applyResidualSeconds(0.6807f)        // 680.7 ms -> 681
        assertEquals(681L, cc.appliedOffsetMs)
    }
}
