package net.packset.app.ui.log

/**
 * Human-readable age of the last ADIF auto-backup, e.g. "Last backup: 5 min ago".
 * Same buckets the Settings screen used before the controls moved to the Log tab.
 */
fun lastBackupLabel(lastBackupAtMs: Long?, nowMs: Long): String {
    val ms = lastBackupAtMs ?: return "Last backup: never"
    val ageMs = nowMs - ms
    return when {
        ageMs < 60_000 -> "Last backup: just now"
        ageMs < 3_600_000 -> "Last backup: ${ageMs / 60_000} min ago"
        ageMs < 86_400_000 -> "Last backup: ${ageMs / 3_600_000} h ago"
        else -> "Last backup: ${ageMs / 86_400_000} d ago"
    }
}
