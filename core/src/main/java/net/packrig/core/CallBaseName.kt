package net.packrig.core

/** Normalizes a callsign to its base form: strips `/portable` and `-suffix`, uppercases. */
object CallBaseName {
    fun of(callsign: String): String? {
        val trimmed = callsign.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.substringBefore('/').substringBefore('-').uppercase()
    }
}
