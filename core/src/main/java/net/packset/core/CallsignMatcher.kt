package net.packset.core

/**
 * Flexible callsign comparison shared by the QSO machine, resume detection,
 * answer selection, and display filtering.
 *
 * Handles compound forms (`K1ABC/P`, `PJ4/K1ABC`) and ft8_lib's hashed forms
 * (`<PJ4/K1ABC>`). Two tokens match when their bracket-stripped forms are
 * equal ignoring case, or when their [base] calls are equal ignoring case AND
 * the base contains a digit — so bare modifiers (DX, POTA, NA) never
 * base-match a callsign.
 */
object CallsignMatcher {

    /**
     * The base callsign: brackets stripped, then the `/`-separated segment
     * that looks most like a callsign (has a digit AND a letter; longest such
     * wins; fallback first segment), minus any `-suffix`, uppercased.
     */
    fun base(call: String): String {
        val segments = stripBrackets(call).split('/').filter { it.isNotEmpty() }
        val candidate = segments
            .filter { seg -> seg.any(Char::isDigit) && seg.any(Char::isLetter) }
            .maxByOrNull { it.length }
            ?: segments.firstOrNull()
            ?: ""
        return candidate.substringBefore('-').uppercase()
    }

    /**
     * Canonical identity key for logbook lookups: bracket-stripped, trimmed,
     * uppercased. Collapses ft8_lib's hashed transport form (`<PJ4/K1ABC>`)
     * onto the logged full call (`PJ4/K1ABC`) while keeping distinct compound
     * identities (`K1ABC` vs `PJ4/K1ABC`) apart.
     */
    fun canonical(call: String): String = stripBrackets(call).uppercase()

    fun matches(token: String, call: String): Boolean {
        val t = stripBrackets(token)
        val c = stripBrackets(call)
        if (t.isBlank() || c.isBlank()) return false
        if (t.equals(c, ignoreCase = true)) return true
        val callBase = base(call)
        return callBase.isNotEmpty() &&
            callBase.any(Char::isDigit) &&
            callBase.equals(base(token), ignoreCase = true)
    }

    private fun stripBrackets(s: String): String =
        s.trim().removePrefix("<").removeSuffix(">")
}
