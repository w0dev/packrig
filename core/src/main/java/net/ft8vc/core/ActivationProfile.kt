package net.ft8vc.core

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

    const val DEFAULT_CQ_MODIFIER = "POTA"
    const val POTA_SIG = "POTA"
}
