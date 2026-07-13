package net.packrig.core

/**
 * ISO 3166 alpha-2 country code lookup for decoded FT8 callsigns.
 *
 * Backed by the generated [CallsignCountryTable] (see tools/gen_country_table.py).
 * Resolution order: exact call, then portable-suffix strip, then prefix
 * designator of a compound call, then longest-prefix match. Every failure
 * mode returns null (rendered as a blank cell) — never a guessed code.
 */
object CallsignCountry {

    private val exact: Map<String, String> by lazy { parseEntries(CallsignCountryTable.EXACT_ENTRIES) }
    private val prefixes: Map<String, String> by lazy { parseEntries(CallsignCountryTable.PREFIX_ENTRIES) }

    /** ISO alpha-2 code for [call], or null when unresolvable. Never throws. */
    fun isoFor(call: String): String? {
        val c = call.trim().uppercase()
        if (c.isEmpty() || c.startsWith("<")) return null
        exact[c]?.let { return it }
        val base = stripPortableSuffix(c) ?: return null
        exact[base]?.let { return it }
        val target = designatorOf(base)
        for (len in minOf(target.length, CallsignCountryTable.MAX_PREFIX_LEN) downTo 1) {
            prefixes[target.substring(0, len)]?.let { return it }
        }
        return null
    }

    /**
     * Drops one trailing portable suffix (`/P`, `/M`, `/QRP`, `/A`, `/<digit>`).
     * Maritime/aeronautical mobile (`/MM`, `/AM`) has no DXCC country: null.
     */
    private fun stripPortableSuffix(call: String): String? {
        val slash = call.lastIndexOf('/')
        if (slash < 0) return call
        val tail = call.substring(slash + 1)
        return when {
            tail == "MM" || tail == "AM" -> null
            tail == "P" || tail == "M" || tail == "QRP" || tail == "A" ||
                (tail.length == 1 && tail[0].isDigit()) ->
                call.substring(0, slash).ifEmpty { null }
            else -> call
        }
    }

    /** For compound calls (`F/DL1ABC`), the shorter segment names the country; ties go first. */
    private fun designatorOf(call: String): String {
        val slash = call.indexOf('/')
        if (slash < 0) return call
        val head = call.substring(0, slash)
        val tail = call.substring(slash + 1)
        return if (tail.length < head.length) tail else head
    }

    private fun parseEntries(chunks: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (chunk in chunks) {
            for (entry in chunk.split(';')) {
                if (entry.isEmpty()) continue
                val eq = entry.indexOf('=')
                map[entry.substring(0, eq)] = entry.substring(eq + 1)
            }
        }
        return map
    }
}
