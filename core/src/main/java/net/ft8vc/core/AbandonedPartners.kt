package net.ft8vc.core

/**
 * In-session blocklist for stations whose QSO was abandoned (no-reply timeout or user Abandon).
 * Prevents auto-resume to incomplete exchanges after abandon or no-reply timeout.
 */
class AbandonedPartners {

    private val blocked = LinkedHashSet<String>()

    fun abandon(callsign: String) {
        baseCall(callsign)?.let { blocked.add(it) }
    }

    fun isAbandoned(callsign: String): Boolean {
        val base = baseCall(callsign) ?: return false
        return blocked.contains(base)
    }

    fun clear() {
        blocked.clear()
    }

    /** Allow manual resume (tap decode) to override the block for one station. */
    fun allowResume(callsign: String) {
        baseCall(callsign)?.let { blocked.remove(it) }
    }

    fun snapshot(): Set<String> = blocked.toSet()

    private fun baseCall(callsign: String): String? {
        val trimmed = callsign.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.substringBefore('/').substringBefore('-').uppercase()
    }
}
