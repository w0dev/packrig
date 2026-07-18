package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPresetsTest {

    @Test
    fun genericDigirigSpeaksYaesuWithRtsPttOnPortZero() {
        val d = RigRegistry.byId(RigRegistry.GENERIC_DIGIRIG)!!
        assertEquals("Digirig — CAT + RTS PTT (generic)", d.displayName)
        assertEquals(38_400, d.defaultBaud)
        assertEquals(0, d.catPortIndex)
        assertEquals(PttMethod.RTS, d.defaultPtt)
        assertNotNull(d.protocolFactory)
    }

    @Test
    fun genericCatDefaultsToCatPtt() {
        val d = RigRegistry.byId(RigRegistry.GENERIC_CAT)!!
        assertEquals("USB CAT cable / built-in USB — CAT PTT (generic)", d.displayName)
        assertEquals(PttMethod.CAT, d.defaultPtt)
        assertNotNull(d.protocolFactory)
    }

    @Test
    fun genericRtsHasNoCat() {
        val d = RigRegistry.byId(RigRegistry.GENERIC_RTS)!!
        assertEquals("Serial PTT only (RTS), no CAT (generic)", d.displayName)
        assertEquals(PttMethod.RTS, d.defaultPtt)
        assertNull(d.protocolFactory)
    }

    @Test
    fun genericYaesuSpecCoversEveryFt8DialPreset() {
        // Wide bounds: 70 cm (432.174 MHz) must be settable so generics offer the full band table.
        val protocol = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory(null)
        assertNotNull(protocol.setFrequencyCommand(432_174_000L))
        assertNotNull(protocol.setFrequencyCommand(1_840_000L))
    }

    @Test
    fun genericsComeAfterNamedModelsInRegistryOrder() {
        val ids = RigRegistry.all.map { it.id }
        assertTrue(ids.indexOf(RigRegistry.GENERIC_DIGIRIG) > ids.indexOf("ftx1"))
        assertEquals(ids.size - 3, ids.indexOf(RigRegistry.GENERIC_DIGIRIG))
    }

    @Test
    fun isGenericHelpers() {
        assertTrue(RigRegistry.isGeneric(RigRegistry.GENERIC_RTS))
        assertTrue(RigRegistry.isCatGeneric(RigRegistry.GENERIC_CAT))
        assertTrue(RigRegistry.isCatGeneric(RigRegistry.GENERIC_DIGIRIG))
        org.junit.Assert.assertFalse(RigRegistry.isCatGeneric(RigRegistry.GENERIC_RTS))
        org.junit.Assert.assertFalse(RigRegistry.isGeneric("ft891"))
    }
}
