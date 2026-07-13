package net.packrig.app.settings

import net.packrig.rig.PttMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class PttMethodMappingTest {

    @Test
    fun everyMethodMapsToTheMatchingPreference() {
        assertEquals(PttPreference.AUTO, PttMethod.AUTO.toPreference())
        assertEquals(PttPreference.CAT, PttMethod.CAT.toPreference())
        assertEquals(PttPreference.RTS, PttMethod.RTS.toPreference())
    }
}
