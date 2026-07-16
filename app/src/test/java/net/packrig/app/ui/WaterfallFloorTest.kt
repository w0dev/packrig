package net.packrig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterfallFloorTest {
    // Field report 2026-07-15: the rig's RX filter leaves the bins above
    // ~2.9 kHz near-silent, so a low percentile measured the filter floor
    // instead of the band noise and the whole passband rendered green/orange.
    // The estimate must land in the in-band noise block, not the silent block.
    @Test fun floor_lands_in_band_noise_not_filter_floor() {
        val quiet = FloatArray(40) { -100f }   // silent filter-rolloff bins
        val noise = FloatArray(60) { -60f }    // in-band noise bins
        val column = quiet + noise
        val floor = Waterfall.estimateFloor(column, column.size, FloatArray(column.size))
        assertEquals(-60f, floor, 0.01f)
    }

    @Test fun floor_of_uniform_column_is_that_level() {
        val column = FloatArray(100) { -75f }
        val floor = Waterfall.estimateFloor(column, column.size, FloatArray(column.size))
        assertEquals(-75f, floor, 0.01f)
    }

    @Test fun floor_ignores_bins_beyond_n() {
        // Only the first n bins count; trailing garbage must not skew the sort.
        val column = FloatArray(100) { -70f } + FloatArray(20) { 0f }
        val floor = Waterfall.estimateFloor(column, 100, FloatArray(100))
        assertEquals(-70f, floor, 0.01f)
    }

    @Test fun floor_rides_above_strong_signals_minority() {
        // A crowded band: 20% of bins carry strong signals. The median must
        // still report the noise level, not the signal level.
        val noise = FloatArray(80) { -60f }
        val signals = FloatArray(20) { -30f }
        val column = noise + signals
        val floor = Waterfall.estimateFloor(column, column.size, FloatArray(column.size))
        assertEquals(-60f, floor, 0.01f)
    }
}
