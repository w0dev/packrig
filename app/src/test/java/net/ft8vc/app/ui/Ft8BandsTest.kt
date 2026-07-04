package net.ft8vc.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ft8BandsTest {
    @Test fun loose_returns_band_for_exact_preset() {
        assertEquals("20m", bandLabelForFreqLoose(14_074_000L))
    }

    @Test fun loose_returns_band_for_nearby_freq() {
        assertEquals("20m", bandLabelForFreqLoose(14_078_500L))
        assertEquals("40m", bandLabelForFreqLoose(7_080_000L))
    }

    @Test fun loose_returns_null_for_far_freq() {
        // 12.000 MHz is > 200 kHz from any preset.
        assertNull(bandLabelForFreqLoose(12_000_000L))
    }

    @Test fun loose_returns_null_for_null_input() {
        assertNull(bandLabelForFreqLoose(null))
    }

    // Field bug 2026-07-04: logging stored the band via exact preset matching
    // while worked-before classification used the loose matcher — a QSO logged
    // with an off-preset dial (waterfall tuning, CAT rounding) stored band=NULL
    // and was permanently invisible to workedBands(). The two derivations must
    // share loose semantics.

    @Test fun logging_band_matches_classification_semantics() {
        val dials = listOf(
            14_074_000L, 14_074_500L, 14_078_500L, 7_071_250L,
            10_136_800L, 21_074_000L, 12_000_000L,
        )
        for (hz in dials) {
            assertEquals("dial $hz Hz", bandLabelForFreqLoose(hz), bandLabelForLogging(hz))
        }
    }

    @Test fun logging_band_present_for_off_preset_dial() {
        assertEquals("20m", bandLabelForLogging(14_075_250L))
    }

    @Test fun logging_band_null_when_freq_unknown() {
        assertNull(bandLabelForLogging(null))
    }
}
