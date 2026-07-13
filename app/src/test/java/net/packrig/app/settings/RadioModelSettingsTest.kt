package net.packrig.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Radio-model persistence contract — no default model (null until set). */
class RadioModelSettingsTest {

    @Test
    fun radioModelDefaultsToNull() {
        assertNull(StationSettings().radioModelId)
    }

    @Test
    fun catPortOverrideDefaultsToNull() {
        assertNull(StationSettings().catPortOverride)
    }

    @Test
    fun fieldsAreAssignable() {
        val s = StationSettings(radioModelId = "ftdx10", catPortOverride = 1)
        assertEquals("ftdx10", s.radioModelId)
        assertEquals(1, s.catPortOverride)
    }
}
