package net.ft8vc.app.ui

/** One FT8 dial preset (band label + VFO frequency). */
data class Ft8DialPreset(val label: String, val hz: Long) {
    val freqMhz: String get() = "%.3f MHz".format(hz / 1_000_000.0)
    val menuText: String get() = "$label  ·  $freqMhz"
}

/** @deprecated Use [Ft8DialPresets]; kept for call sites that still reference the old name. */
typealias DataBand = Ft8DialPreset

/**
 * Common FT8 dial frequencies by band. Multiple entries per band cover common operating
 * spots (e.g. 20m: 14.071, 14.074, 14.090 MHz).
 */
val Ft8DialPresets = listOf(
    Ft8DialPreset("160m", 1_840_000L),
    Ft8DialPreset("80m", 3_573_000L),
    Ft8DialPreset("60m", 5_357_000L),
    Ft8DialPreset("40m", 7_074_000L),
    Ft8DialPreset("40m", 7_071_000L),
    Ft8DialPreset("30m", 10_136_000L),
    Ft8DialPreset("30m", 10_133_000L),
    Ft8DialPreset("20m", 14_074_000L),
    Ft8DialPreset("20m", 14_071_000L),
    Ft8DialPreset("20m", 14_090_000L),
    Ft8DialPreset("17m", 18_100_000L),
    Ft8DialPreset("15m", 21_074_000L),
    Ft8DialPreset("15m", 21_091_000L),
    Ft8DialPreset("12m", 24_915_000L),
    Ft8DialPreset("10m", 28_074_000L),
)

/** @deprecated Use [Ft8DialPresets]. */
val Ft8DialBands = Ft8DialPresets

fun dialPresetForFreq(hz: Long?): Ft8DialPreset? =
    hz?.let { target -> Ft8DialPresets.firstOrNull { it.hz == target } }

fun bandLabelForFreq(hz: Long?): String? = dialPresetForFreq(hz)?.label

fun dialLabelText(hz: Long?): String =
    dialPresetForFreq(hz)?.let { "Dial ${it.freqMhz}" }
        ?: hz?.let { "Dial %.3f MHz".format(it / 1_000_000.0) }
        ?: "Dial —"

/**
 * Returns the band label of the dial preset whose frequency is closest to [hz],
 * provided it's within 200 kHz. Use when you need a band classification for an
 * arbitrary rig frequency (worked-before lookups, etc.) — for exact preset
 * matching prefer [bandLabelForFreq].
 */
fun bandLabelForFreqLoose(hz: Long?): String? {
    if (hz == null) return null
    val best = Ft8DialPresets.minByOrNull { kotlin.math.abs(it.hz - hz) } ?: return null
    return if (kotlin.math.abs(best.hz - hz) <= 200_000L) best.label else null
}
