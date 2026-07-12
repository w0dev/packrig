package net.packset.app.settings

import net.packset.rig.PttMethod
import net.packset.rig.RigProfile
import net.packset.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class RigCardSummaryTest {

    private fun p(presetId: String, baud: Int? = null, ptt: PttMethod? = null) =
        RigProfile(id = "x", name = "Rig", presetId = presetId, baud = baud, pttMethod = ptt)

    @Test
    fun namedPresetWithDefaults() {
        assertEquals("Yaesu FT-891 — CAT @ 38400, auto PTT", RigCardSummary.subtitle(p("ft891")))
    }

    @Test
    fun overridesWinOverPresetDefaults() {
        assertEquals(
            "Yaesu FT-891 — CAT @ 4800, CAT PTT",
            RigCardSummary.subtitle(p("ft891", baud = 4800, ptt = PttMethod.CAT)),
        )
    }

    @Test
    fun pttOnlyGenericHasNoCat() {
        assertEquals(
            "Serial PTT only (RTS), no CAT (generic) — no CAT, RTS PTT",
            RigCardSummary.subtitle(p(RigRegistry.GENERIC_RTS)),
        )
    }

    @Test
    fun unknownPresetIsDefensive() {
        assertEquals("Unknown preset", RigCardSummary.subtitle(p("not-a-preset")))
    }
}
