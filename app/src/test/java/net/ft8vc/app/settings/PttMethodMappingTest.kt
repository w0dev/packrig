package net.ft8vc.app.settings

import net.ft8vc.app.toPreference
import net.ft8vc.rig.PttMethod
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
