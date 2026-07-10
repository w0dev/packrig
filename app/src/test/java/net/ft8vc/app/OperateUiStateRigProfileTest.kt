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
}
