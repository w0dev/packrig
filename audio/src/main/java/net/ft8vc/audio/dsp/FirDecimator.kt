package net.ft8vc.audio.dsp

/**
 * Streaming FIR low-pass decimator.
 *
 * USB sound cards typically deliver 48 kHz; FT8 DSP wants 12 kHz. This filters
 * out energy above the output Nyquist (to avoid aliasing) and keeps every
 * [factor]-th sample. State is retained between [process] calls so it works on
 * a continuous capture stream.
 */
class FirDecimator(
    private val factor: Int,
    private val taps: DoubleArray,
) {
    init {
        require(factor >= 1) { "factor must be >= 1" }
        require(taps.isNotEmpty()) { "taps must not be empty" }
    }

    private val numTaps = taps.size
    private val history = DoubleArray(numTaps)
    private var writePos = 0
    private var decimCounter = 0

    fun process(input: ShortArray): ShortArray {
        val out = ShortArray(estimateOutput(input.size))
        var outIdx = 0
        for (sample in input) {
            history[writePos] = sample.toDouble()
            writePos = (writePos + 1) % numTaps
            decimCounter++
            if (decimCounter >= factor) {
                decimCounter = 0
                var acc = 0.0
                // taps[0] aligns with the newest sample.
                var idx = (writePos - 1 + numTaps) % numTaps
                for (t in 0 until numTaps) {
                    acc += taps[t] * history[idx]
                    idx = (idx - 1 + numTaps) % numTaps
                }
                out[outIdx++] = clipToShort(acc)
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    private fun estimateOutput(inputLen: Int): Int = (inputLen + decimCounter) / factor + 1

    private fun clipToShort(v: Double): Short {
        val r = Math.round(v)
        return when {
            r > Short.MAX_VALUE -> Short.MAX_VALUE
            r < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> r.toShort()
        }
    }

    companion object {
        /**
         * Design a Hann-windowed-sinc low-pass and return a decimator.
         *
         * @param inputRate  incoming sample rate (e.g. 48000)
         * @param factor     decimation factor (e.g. 4 -> 12000)
         * @param numTaps    filter length (odd is fine; longer = sharper)
         */
        fun lowPass(inputRate: Int, factor: Int, numTaps: Int = 63): FirDecimator {
            val outputNyquist = inputRate.toDouble() / (2.0 * factor)
            // Cut a bit below the output Nyquist to leave transition room.
            val cutoffHz = 0.9 * outputNyquist
            val fc = cutoffHz / inputRate // normalized (cycles/sample)
            val taps = DoubleArray(numTaps)
            val mid = (numTaps - 1) / 2.0
            var sum = 0.0
            for (n in 0 until numTaps) {
                val x = n - mid
                val sinc = if (x == 0.0) 2.0 * fc else Math.sin(2.0 * Math.PI * fc * x) / (Math.PI * x)
                val hann = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (numTaps - 1))
                taps[n] = sinc * hann
                sum += taps[n]
            }
            // Normalize for unity DC gain.
            for (n in 0 until numTaps) taps[n] /= sum
            return FirDecimator(factor, taps)
        }
    }
}
