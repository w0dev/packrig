package net.ft8vc.app

/**
 * Drops repeats of the most recently shown snackbar text within [windowMs].
 *
 * The operate snackbar queue is drained sequentially (each event holds the
 * host for its full duration), so a repeating failure — e.g. the TX preflight
 * error on every attempt — otherwise stacks identical 10-second banners
 * (2026-07-03 field report). Only the last shown text is tracked: alternating
 * messages still show, and a suppressed repeat does not extend the window.
 */
class SnackbarThrottle(
    private val windowMs: Long = WINDOW_MS_DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var lastShownText: String? = null
    private var lastShownAtMs = 0L

    /** True if [text] should be shown now; records it as shown when so. */
    fun shouldShow(text: String): Boolean {
        val now = clock()
        if (text == lastShownText && now - lastShownAtMs < windowMs) return false
        lastShownText = text
        lastShownAtMs = now
        return true
    }

    companion object {
        /** Matches the ERROR snackbar duration (SnackbarDuration.Long). */
        const val WINDOW_MS_DEFAULT = 10_000L
    }
}
