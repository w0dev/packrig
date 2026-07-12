package net.packset.core

/**
 * In-session station suppression, split by intent:
 *  - [userBlocked]: stations the operator explicitly blocked (long-press). Hidden
 *    from the decode list and shown in the Settings blocklist manager.
 *  - [autoSuppressed]: stations dropped by the no-reply timeout. Excluded from
 *    auto-answer/CQ selection only; never hidden, never shown in the manager.
 * Auto-answer/CQ exclusion uses the union ([snapshot]).
 */
class AbandonedPartners {

    private val userBlocked = LinkedHashSet<String>()
    private val autoSuppressed = LinkedHashSet<String>()

    /** Explicit operator block (long-press). */
    fun blockUser(callsign: String) {
        CallBaseName.of(callsign)?.let { userBlocked.add(it) }
    }

    /** Transient no-reply suppression (auto-answer exclusion only). */
    fun suppressAuto(callsign: String) {
        CallBaseName.of(callsign)?.let { autoSuppressed.add(it) }
    }

    fun isUserBlocked(callsign: String): Boolean {
        val base = CallBaseName.of(callsign) ?: return false
        return userBlocked.contains(base)
    }

    /** Manual "engage this station now" override (tap-to-resume / manager unblock). */
    fun allowResume(callsign: String) {
        val base = CallBaseName.of(callsign) ?: return
        userBlocked.remove(base)
        autoSuppressed.remove(base)
    }

    fun clear() {
        userBlocked.clear()
        autoSuppressed.clear()
    }

    /** Union of both sets: everything excluded from auto-answer/CQ selection. */
    fun snapshot(): Set<String> = LinkedHashSet(userBlocked).apply { addAll(autoSuppressed) }

    /** User-blocked stations only (row hiding + Settings manager). */
    fun userBlockedSnapshot(): Set<String> = userBlocked.toSet()
}
