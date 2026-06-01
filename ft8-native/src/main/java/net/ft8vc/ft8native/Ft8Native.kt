package net.ft8vc.ft8native

import android.util.Log

/**
 * Kotlin entry point to the native FT8 core (`libft8vc.so`).
 *
 * Phase 0 exposes only [version] to prove the JNI bridge loads. Phase 2 adds
 * decode/encode entry points wrapping kgoba/ft8_lib.
 */
object Ft8Native {

    private const val TAG = "Ft8Native"

    private val loaded: Boolean = try {
        System.loadLibrary("ft8vc")
        true
    } catch (t: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load libft8vc.so", t)
        false
    }

    fun isAvailable(): Boolean = loaded

    /** Native build identifier, or a fallback string if the library is unavailable. */
    fun version(): String =
        if (loaded) {
            runCatching { nativeVersion() }.getOrDefault("error")
        } else {
            "not loaded"
        }

    private external fun nativeVersion(): String
}
