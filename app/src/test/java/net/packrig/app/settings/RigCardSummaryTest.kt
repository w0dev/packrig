package net.packrig.app.settings

import net.packrig.rig.PttMethod
import net.packrig.rig.RigProfile
import net.packrig.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class RigCardSummaryTest {

    private fun p(presetId: String, baud: Int? = null, ptt: PttMethod? = null) =
        RigProfile(id = "x", name = "Rig", presetId = presetId, baud = baud, pttMethod = ptt)

    @Test
    fun namedPresetWithDefaults() {
        assertEquals("CAT @ 38400, auto PTT", RigCardSummary.subtitle(p("ft891")))
    }

    @Test
    fun overridesWinOverPresetDefaults() {
        assertEquals(
            "CAT @ 4800, CAT PTT",
            RigCardSummary.subtitle(p("ft891", baud = 4800, ptt = PttMethod.CAT)),
        )
    }

    @Test
    fun pttOnlyGenericHasNoCat() {
        assertEquals(
            "no CAT, RTS PTT",
            RigCardSummary.subtitle(p(RigRegistry.GENERIC_RTS)),
        )
    }

    @Test
    fun unknownPresetIsDefensive() {
        assertEquals("Unknown preset", RigCardSummary.subtitle(p("not-a-preset")))
    }
}
