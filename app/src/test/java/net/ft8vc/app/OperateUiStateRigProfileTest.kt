package net.ft8vc.app

import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperateUiStateRigProfileTest {

    @Test
    fun selectedProfileNameResolvesFromList() {
        val state = OperateUiState(
            rigProfiles = listOf(RigProfile(id = "u1", name = "Home 891", presetId = "ft891")),
            selectedRigProfileId = "u1",
        )
        assertEquals("Home 891", state.selectedRigProfileName)
    }

    @Test
    fun rigHasCatComputation() {
        assertTrue(OperateUiState(radioModelId = "ft891").computeRigHasCat())
        assertFalse(OperateUiState(radioModelId = RigRegistry.GENERIC_RTS).computeRigHasCat())
        assertTrue(OperateUiState(radioModelId = null).computeRigHasCat())
        assertTrue(OperateUiState(radioModelId = "unknown-model").computeRigHasCat())
    }

    @Test
    fun effectiveDialPrefersCatReadbackAndFallsToManualOnlyWithoutCat() {
        // CAT rig: readback wins; stale manual dial must NOT leak in when CAT is silent.
        assertEquals(
            14_074_000L,
            OperateUiState(rigFreqHz = 14_074_000L, lastDialFreqHz = 7_074_000L, rigHasCat = true).effectiveDialFreqHz,
        )
        assertEquals(
            null,
            OperateUiState(rigFreqHz = null, lastDialFreqHz = 7_074_000L, rigHasCat = true).effectiveDialFreqHz,
        )
        // No-CAT rig: the band-picker value is the dial.
        assertEquals(
            7_074_000L,
            OperateUiState(rigFreqHz = null, lastDialFreqHz = 7_074_000L, rigHasCat = false).effectiveDialFreqHz,
        )
    }
}
