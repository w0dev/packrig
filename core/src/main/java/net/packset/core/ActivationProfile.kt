package net.packset.core

/** Portable / POTA activation helpers (pure Kotlin, unit-testable). */
object ActivationProfile {
    private val PARK_REF = Regex("^[A-Z]{1,4}-\\d{4,5}(@[A-Z0-9-]+)?$", RegexOption.IGNORE_CASE)

    /** CQ modifier when POTA mode is on (e.g. `POTA` → `CQ POTA CALL GR`). */
    fun cqModifier(potaEnabled: Boolean, modifier: String = DEFAULT_CQ_MODIFIER): String? {
        if (!potaEnabled) return null
        return modifier.trim().uppercase().takeIf { it.isNotEmpty() }
    }

    /** Normalize park reference for ADIF `MY_SIG_INFO`, or null if invalid. */
    fun normalizeParkRef(ref: String): String? {
        val normalized = ref.trim().uppercase()
        return normalized.takeIf { PARK_REF.matches(it) }
    }

    fun isValidParkRef(ref: String): Boolean = normalizeParkRef(ref) != null

    /** Tolerant split of a comma-separated input into normalized refs; invalid/blank entries dropped. */
    fun parseParkRefs(raw: String?): List<String> =
        raw.orEmpty().split(',').mapNotNull { normalizeParkRef(it) }

    /** Normalized, deduped CSV for storage/ADIF, or null when nothing valid remains. */
    fun formatParkRefs(refs: List<String>): String? =
        refs.mapNotNull { normalizeParkRef(it) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    /** Strict gate: at least one entry and EVERY comma-separated entry is a valid park ref. */
    fun isValidParkRefList(raw: String): Boolean {
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return entries.isNotEmpty() && entries.all { isValidParkRef(it) }
    }

    /** CSV to stamp on a completed QSO, or null when POTA mode is off or no ref is valid. */
    fun parkRefsForLogging(potaEnabled: Boolean, raw: String): String? {
        if (!potaEnabled) return null
        return formatParkRefs(parseParkRefs(raw))
    }

    const val DEFAULT_CQ_MODIFIER = "POTA"
    const val POTA_SIG = "POTA"
}
