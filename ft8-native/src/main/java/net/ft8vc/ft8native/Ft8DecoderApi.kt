package net.ft8vc.ft8native

/**
 * Decoder seam taken by Phase 3's `DecodeController` and Phase 5's
 * `TxOrchestrator`. Mirrors the surface of the [Ft8Native] singleton so
 * production code constructs `Ft8Native` directly while tests substitute
 * `Ft8DecoderFake`. Hides the JNI layer from controller code and keeps
 * tests independent of the loaded native library.
 */
interface Ft8DecoderApi {
    fun isAvailable(): Boolean
    fun version(): String
    fun decode(samples: ShortArray, sampleRate: Int = 12_000): Array<Ft8DecodeResult>
    fun encode(message: String, freqHz: Float = 1000f, sampleRate: Int = 12_000): ShortArray
}
