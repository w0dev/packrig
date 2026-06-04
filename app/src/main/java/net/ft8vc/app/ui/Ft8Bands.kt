package net.ft8vc.app.ui

/** Common FT8 dial frequencies (VFO-A) by band. */
data class DataBand(val label: String, val hz: Long) {
    val freqMhz: String get() = "%.3f MHz".format(hz / 1_000_000.0)
    val menuText: String get() = "$label  ·  $freqMhz"
}

val Ft8DialBands = listOf(
    DataBand("160m", 1_840_000L),
    DataBand("80m", 3_573_000L),
    DataBand("60m", 5_357_000L),
    DataBand("40m", 7_074_000L),
    DataBand("30m", 10_136_000L),
    DataBand("20m", 14_074_000L),
    DataBand("17m", 18_100_000L),
    DataBand("15m", 21_074_000L),
    DataBand("12m", 24_915_000L),
    DataBand("10m", 28_074_000L),
)

fun bandLabelForFreq(hz: Long?): String? =
    Ft8DialBands.firstOrNull { it.hz == hz }?.label
