package net.packset.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 4-character Maidenhead locator helpers for distance-based answer selection. */
object MaidenheadGrid {

    private val GRID4 = Regex("^[A-R]{2}[0-9]{2}$", RegexOption.IGNORE_CASE)

    fun isValid4(grid: String): Boolean = GRID4.matches(grid.trim())

    /** Center of a 4-char grid square in degrees (lat, lon). */
    fun centerLatLon(grid: String): Pair<Double, Double>? {
        val g = grid.trim().uppercase()
        if (g.length < 4 || !isValid4(g.take(4))) return null
        val lon = (g[0].code - 'A'.code) * 20.0 - 180.0 +
            (g[2].code - '0'.code) * 2.0 + 1.0
        val lat = (g[1].code - 'A'.code) * 10.0 - 90.0 +
            (g[3].code - '0'.code) + 0.5
        return lat to lon
    }

    /** Great-circle distance in km between two 4-char grids, or null if either is invalid. */
    fun distanceKm(fromGrid: String, toGrid: String): Double? {
        val a = centerLatLon(fromGrid) ?: return null
        val b = centerLatLon(toGrid) ?: return null
        return haversineKm(a.first, a.second, b.first, b.second)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
