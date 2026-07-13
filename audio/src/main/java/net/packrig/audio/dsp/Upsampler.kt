package net.packrig.audio.dsp

/**
 * Upsample mono PCM by an integer factor (e.g. 12 kHz -> 48 kHz for USB playback).
 * Uses linear interpolation between samples.
 */
object Upsampler {

    fun linear(input: ShortArray, factor: Int): ShortArray {
        if (factor <= 1 || input.isEmpty()) return input
        val outLen = input.size * factor
        val out = ShortArray(outLen)
        val ratio = 1.0 / factor
        for (i in 0 until outLen) {
            val src = i * ratio
            val idx = src.toInt().coerceIn(0, input.size - 1)
            val frac = src - idx
            val a = input[idx].toInt()
            val b = input[minOf(idx + 1, input.size - 1)].toInt()
            val v = a + frac * (b - a)
            out[i] = v.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }
}
