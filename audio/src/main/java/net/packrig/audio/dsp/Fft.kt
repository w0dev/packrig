package net.packrig.audio.dsp

/**
 * Minimal in-place iterative radix-2 Cooley-Tukey FFT.
 *
 * Pure and allocation-light so it can be unit-tested on the JVM and reused for
 * the live waterfall. Phase 2's decoder uses the native ft8_lib DSP instead;
 * this exists for display-side spectra.
 */
class Fft(val size: Int) {

    init {
        require(size > 0 && (size and (size - 1)) == 0) {
            "FFT size must be a power of two, got $size"
        }
    }

    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)

    init {
        for (i in 0 until size / 2) {
            val angle = -2.0 * Math.PI * i / size
            cosTable[i] = Math.cos(angle)
            sinTable[i] = Math.sin(angle)
        }
    }

    /**
     * In-place complex FFT. [re] and [im] must each have length [size].
     * On return they hold the transform.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "input arrays must have length $size" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        // Danielson-Lanczos.
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (jj in i until i + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[jj + half] * wr - im[jj + half] * wi
                    val ti = re[jj + half] * wi + im[jj + half] * wr
                    re[jj + half] = re[jj] - tr
                    im[jj + half] = im[jj] - ti
                    re[jj] += tr
                    im[jj] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
