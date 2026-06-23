package net.ft8vc.ft8native

import android.util.Log

/**
 * Kotlin entry point to the native FT8 core (`libft8vc.so`).
 *
 * Wraps kgoba/ft8_lib. [decode] takes one slot of 12 kHz mono PCM and returns
 * the messages found in it. Encode/TX is added in Phase 3.
 */
object Ft8Native : Ft8DecoderApi {

    private const val TAG = "Ft8Native"

    private val loaded: Boolean = try {
        System.loadLibrary("ft8vc")
        true
    } catch (t: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load libft8vc.so", t)
        false
    }

    override fun isAvailable(): Boolean = loaded

    /** Native build identifier, or a fallback string if the library is unavailable. */
    override fun version(): String =
        if (loaded) {
            runCatching { nativeVersion() }.getOrDefault("error")
        } else {
            "not loaded"
        }

    /**
     * Decode one FT8 slot. [samples] is mono 16-bit PCM at [sampleRate] (12 kHz),
     * ideally ~15 seconds aligned to the UTC slot boundary. Returns an empty array
     * if nothing decodes or the native library is unavailable.
     */
    override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
        if (loaded) {
            runCatching { nativeDecode(samples, sampleRate) }.getOrDefault(emptyArray())
        } else {
            emptyArray()
        }

    /**
     * Encode [message] to FT8 PCM at [sampleRate] (12 kHz mono).
     *
     * When [offsetSymbols] == 0 (default), returns a full 15-second slot buffer
     * with silence padding so the transmission is centered (v1.0 behavior, byte-
     * identical to the no-parameter call path).
     *
     * When [offsetSymbols] > 0, returns a truncated buffer containing only symbols
     * `[offsetSymbols, 79)` — `(79 - offsetSymbols) * 1920` samples, no silence
     * padding. The full FEC-encoded message is still synthesized over all 79
     * symbols; only the on-air PCM is clipped. Used by late-start TX.
     *
     * Returns an empty array if the message can't be encoded or the native library
     * is missing.
     */
    override fun encode(
        message: String,
        freqHz: Float,
        sampleRate: Int,
        offsetSymbols: Int,
    ): ShortArray =
        if (loaded) {
            runCatching { nativeEncode(message, freqHz, sampleRate, offsetSymbols) }.getOrDefault(ShortArray(0))
        } else {
            ShortArray(0)
        }

    private external fun nativeVersion(): String

    private external fun nativeDecode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult>

    private external fun nativeEncode(
        message: String,
        freqHz: Float,
        sampleRate: Int,
        offsetSymbols: Int,
    ): ShortArray
}
