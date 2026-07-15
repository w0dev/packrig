package net.packrig.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A scrolling waterfall image. New spectrum columns are appended at the bottom
 * and older rows scroll upward. dB values are mapped to color with an adaptive
 * noise floor so the display stays readable as signal levels change.
 *
 * All mutation is guarded by [lock] because columns arrive on the capture thread
 * while [snapshot] is read on the UI thread.
 */
class Waterfall(
    val bins: Int,
    val history: Int = 320,
) {
    /** dB above the estimated noise floor where the color ramp starts (higher = darker). */
    @Volatile
    var floorOffsetDb: Float = 8f

    /** dB span of the color ramp from floor to full-scale red (smaller = more contrast). */
    @Volatile
    var rangeDb: Float = 45f

    private val lock = Any()
    private val bitmap: Bitmap = Bitmap.createBitmap(bins, history, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(bins * history)
    private val scratch = FloatArray(bins)
    private var emaFloor = -80f
    private var primed = false

    init {
        pixels.fill(0xFF000000.toInt())
    }

    fun addColumn(column: FloatArray) {
        synchronized(lock) {
            val n = minOf(bins, column.size)
            val floorSample = estimateFloor(column, n, scratch)

            if (!primed) {
                emaFloor = floorSample
                primed = true
            } else {
                emaFloor += 0.1f * (floorSample - emaFloor)
            }
            val floor = emaFloor + floorOffsetDb
            val scale = 1f / rangeDb

            // Scroll up one row.
            System.arraycopy(pixels, bins, pixels, 0, bins * (history - 1))
            val base = bins * (history - 1)
            for (x in 0 until n) {
                val norm = ((column[x] - floor) * scale).coerceIn(0f, 1f)
                pixels[base + x] = colorFor(norm)
            }
        }
    }

    /** Push the latest pixels into the backing bitmap and return it for drawing. */
    fun snapshot(): ImageBitmap {
        synchronized(lock) {
            bitmap.setPixels(pixels, 0, bins, 0, 0, bins, history)
        }
        return bitmap.asImageBitmap()
    }

    fun clear() {
        synchronized(lock) {
            pixels.fill(0xFF000000.toInt())
            primed = false
        }
    }

    /**
     * Waterfall ramp biased dark: the bottom ~55% of the range stays
     * black -> blue (noise), the top half runs cyan -> green -> yellow -> red
     * (signals). WSJT-X-like contrast so individual FT8 traces separate.
     */
    private fun colorFor(t: Float): Int {
        val r: Int
        val g: Int
        val b: Int
        when {
            t < 0.35f -> { val u = t / 0.35f; r = 0; g = 0; b = (170 * u).toInt() }
            t < 0.55f -> { val u = (t - 0.35f) / 0.2f; r = 0; g = (200 * u).toInt(); b = (170 + 85 * u).toInt() }
            t < 0.72f -> { val u = (t - 0.55f) / 0.17f; r = 0; g = (200 + 55 * u).toInt(); b = (255 * (1 - u)).toInt() }
            t < 0.88f -> { val u = (t - 0.72f) / 0.16f; r = (255 * u).toInt(); g = 255; b = 0 }
            else -> { val u = (t - 0.88f) / 0.12f; r = 255; g = (255 - 155 * u).toInt(); b = 0 }
        }
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    companion object {
        /**
         * Noise-floor estimate for one spectrum column: the median of the
         * first [n] bins. The rig's RX filter leaves a large block of bins
         * near-silent, so a low percentile measures the filter floor instead
         * of the band noise and washes the display out; the median lands in
         * the in-band noise block while still sitting below signal bins.
         *
         * Sorts into [scratch] (no allocation; callers pass a reusable buffer
         * of at least [n] floats).
         */
        fun estimateFloor(column: FloatArray, n: Int, scratch: FloatArray): Float {
            System.arraycopy(column, 0, scratch, 0, n)
            java.util.Arrays.sort(scratch, 0, n)
            return scratch[n / 2]
        }
    }
}
