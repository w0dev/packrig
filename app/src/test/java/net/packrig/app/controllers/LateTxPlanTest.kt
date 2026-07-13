package net.packrig.app.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LateTxPlanTest {

    @Test
    fun toggleOffAlwaysReturnsNormal() {
        for (t in 0L..14_999L step 50L) {
            assertEquals("t=$t", LateTxPlan.Normal, computeLateTxPlan(t, toggleEnabled = false))
        }
    }

    @Test
    fun belowFloorReturnsNormal() {
        for (t in 0L..1_339L step 10L) {
            assertEquals("t=$t", LateTxPlan.Normal, computeLateTxPlan(t, toggleEnabled = true))
        }
    }

    @Test
    fun aboveCutoffReturnsDeferred() {
        for (t in 7_001L..14_999L step 50L) {
            assertEquals("t=$t", LateTxPlan.Deferred, computeLateTxPlan(t, toggleEnabled = true))
        }
    }

    @Test
    fun atFloorReturnsLateOneSymbol() {
        val plan = computeLateTxPlan(1_340L, toggleEnabled = true)
        assertTrue("plan was $plan", plan is LateTxPlan.Late)
        plan as LateTxPlan.Late
        assertEquals(1, plan.offsetSymbols)
        assertTrue("waitMs=${plan.waitMs}", plan.waitMs in 0L..160L)
    }

    @Test
    fun atCutoffReturnsLateThirtySeven() {
        val plan = computeLateTxPlan(7_000L, toggleEnabled = true)
        assertTrue("plan was $plan", plan is LateTxPlan.Late)
        plan as LateTxPlan.Late
        assertEquals(37, plan.offsetSymbols)
        assertTrue("waitMs=${plan.waitMs}", plan.waitMs in 0L..160L)
    }

    @Test
    fun lateWindowProducesValidPlans() {
        for (t in 1_340L..7_000L step 25L) {
            val plan = computeLateTxPlan(t, toggleEnabled = true)
            assertTrue("t=$t produced $plan", plan is LateTxPlan.Late)
            plan as LateTxPlan.Late
            assertTrue("offset=${plan.offsetSymbols}", plan.offsetSymbols in 1..37)
            assertTrue("waitMs=${plan.waitMs}", plan.waitMs in 0L..159L)

            // Invariant: 1180 + offset*160 - t - waitMs ∈ [0, 160) ms (ms-rounded)
            val keyMomentInSlot = 1180L + plan.offsetSymbols * 160L
            val drift = keyMomentInSlot - t - plan.waitMs
            assertTrue("t=$t offset=${plan.offsetSymbols} wait=${plan.waitMs} drift=$drift",
                drift in -1L..1L)
        }
    }

    @Test
    fun walkThroughExample_t3200() {
        // Spec walk-through: t=3.200 → offsetSymbols=13, waitMs=60
        // (key moment = 1180 + 13×160 = 3260; wait = 3260 - 3200 = 60ms)
        val plan = computeLateTxPlan(3_200L, toggleEnabled = true)
        assertEquals(LateTxPlan.Late(offsetSymbols = 13, waitMs = 60L), plan)
    }
}
