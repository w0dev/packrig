package net.packrig.audio.dsp

/**
 * Converts a stream of mono PCM samples into magnitude (dB) columns suitable
 * for a scrolling waterfall.
 *
 * Uses a Hann window with 50% overlap. Only bins up to [maxFreqHz] are returned
 * since FT8 audio lives roughly in the 0-4000 Hz passband.
 */
class SpectrumProcessor(
    private val sampleRate: Int = 12_000,
    private val fftSize: Int = 2048,
    maxFreqHz: Int = 4000,
) {
    val binWidthHz: Double = sampleRate.toDouble() / fftSize
    val binCount: Int = (maxFreqHz / binWidthHz).toInt().coerceIn(1, fftSize / 2)

    fun freqForBin(bin: Int): Double = bin * binWidthHz

    private val fft = Fft(fftSize)
    private val window = DoubleArray(fftSize) {
        0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (fftSize - 1))
    }
    private val buffer = DoubleArray(fftSize)
    private var fill = 0
    private val hop = fftSize / 2

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    /** Feed samples; [emit] is called once per completed (overlapped) window. */
    fun process(samples: ShortArray, emit: (FloatArray) -> Unit) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(fftSize - fill, samples.size - i)
            for (k in 0 until n) {
                buffer[fill + k] = samples[i + k].toDouble()
            }
            fill += n
            i += n

            if (fill == fftSize) {
                emit(computeColumn())
                // Slide by hop: keep the last (fftSize - hop) samples.
                System.arraycopy(buffer, hop, buffer, 0, fftSize - hop)
                fill = fftSize - hop
            }
        }
    }

    private fun computeColumn(): FloatArray {
        for (n in 0 until fftSize) {
            re[n] = buffer[n] * window[n]
            im[n] = 0.0
        }
        fft.transform(re, im)

        val out = FloatArray(binCount)
        val norm = 1.0 / fftSize
        for (b in 0 until binCount) {
            val mag = Math.hypot(re[b], im[b]) * norm
            out[b] = (20.0 * Math.log10(mag + 1e-9)).toFloat()
        }
        return out
    }
}
