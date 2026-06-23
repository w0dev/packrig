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
}
