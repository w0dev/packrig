package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IcomModelsTest {

    @Test
    fun addressesMatchCrossCheckedFactoryDefaults() {
        assertEquals(0x94, IcomModels.IC7300.civAddress)
        assertEquals(0xA4, IcomModels.IC705.civAddress)
        assertEquals(0x88, IcomModels.IC7100.civAddress)
        assertEquals(0x70, IcomModels.XIEGU_G90.civAddress)
        assertEquals(0xA4, IcomModels.XIEGU_X6100.civAddress)
    }

    @Test
    fun dataModeStrategiesMatchRigGenerations() {
        assertEquals(DataModeStrategy.CMD_26, IcomModels.IC7300.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_26, IcomModels.IC705.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_06_PLUS_1A, IcomModels.IC7100.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_06_ONLY, IcomModels.XIEGU_G90.dataModeStrategy)
        assertEquals(DataModeStrategy.CMD_26, IcomModels.XIEGU_X6100.dataModeStrategy)
    }

    @Test
    fun tuningBoundsGateFt8DialPresets() {
        // HF-only G90 rejects 6 m; VHF/UHF-capable IC-705 accepts 70 cm.
        assertNull(IcomCiV(IcomModels.XIEGU_G90).setFrequencyCommand(50_313_000L))
        assertNotNull(IcomCiV(IcomModels.IC705).setFrequencyCommand(432_174_000L))
        assertNotNull(IcomCiV(IcomModels.IC7300).setFrequencyCommand(50_313_000L))
        assertNull(IcomCiV(IcomModels.IC7300).setFrequencyCommand(144_174_000L))
        assertNotNull(IcomCiV(IcomModels.XIEGU_X6100).setFrequencyCommand(50_313_000L))
    }
}
