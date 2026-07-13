package net.packrig.data.adif

import net.packrig.data.model.QsoContact
import kotlin.math.abs

/** Duplicate-skip merge for ADIF import (spec 2026-07-04-durable-adif-backup). */
object AdifImportMerge {

    /** Same call + band within this window is the same QSO (minute-precision files). */
    const val DUP_WINDOW_MS = 90_000L

    /**
     * Split [incoming] into (contacts to insert, duplicate count) against
     * [existing]. Accepted contacts join the comparison set so duplicates
     * inside the imported file itself also collapse.
     */
    fun partition(
        existing: List<QsoContact>,
        incoming: List<QsoContact>,
    ): Pair<List<QsoContact>, Int> {
        val accepted = mutableListOf<QsoContact>()
        val seen = existing.toMutableList()
        var duplicates = 0
        for (candidate in incoming) {
            if (seen.any { isDuplicate(it, candidate) }) {
                duplicates++
            } else {
                accepted += candidate
                seen += candidate
            }
        }
        return accepted to duplicates
    }

    private fun isDuplicate(a: QsoContact, b: QsoContact): Boolean =
        a.dxCall.equals(b.dxCall, ignoreCase = true) &&
            a.band == b.band &&
            abs(a.utcMillis - b.utcMillis) < DUP_WINDOW_MS
}
