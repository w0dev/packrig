package net.ft8vc.core

/**
 * Suppresses duplicate log rows when the same station completes again within
 * [windowMs] — the lost-RR73 retry case: we re-confirm on air but must not
 * write a second log entry. Keyed by [CallsignMatcher.base] so compound and
 * hashed retry forms share one window.
 */
class DupeLogGuard(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val lastLoggedAt = HashMap<String, Long>()

    /** True when a completion for [dxCall] at [nowMs] should be logged; records it if so. */
    fun shouldLog(dxCall: String, nowMs: Long): Boolean {
        val key = CallsignMatcher.base(dxCall)
        val last = lastLoggedAt[key]
        if (last != null && nowMs - last < windowMs) return false
        lastLoggedAt[key] = nowMs
        return true
    }

    fun clear() = lastLoggedAt.clear()

    companion object {
        /** 10 minutes — long enough for any retry chain, short enough for real re-works. */
        const val DEFAULT_WINDOW_MS = 600_000L
    }
}
