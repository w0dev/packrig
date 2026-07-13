package net.packrig.app.settings

/**
 * QRZ API key input format: 16 alphanumerics rendered as XXXX-XXXX-XXXX-XXXX.
 * The editable text is the raw 16 characters; dashes exist only in the
 * visual layer, so they appear the moment a group completes and can never
 * be deleted or backspaced over individually. Pure functions, JVM-tested.
 */
object QrzKeyFormat {

    private const val RAW_LENGTH = 16
    private const val GROUP = 4

    /** Editable text from any source (typing, stored key): A–Z/0–9 only, max 16. */
    fun sanitize(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }.take(RAW_LENGTH)

    /** Persisted/sent form: dash-grouped, no trailing dash. */
    fun store(raw: String): String = raw.chunked(GROUP).joinToString("-")

    /**
     * On-screen form: dash-grouped with an enforced trailing dash while a
     * group has just completed (raw length 4/8/12); masked shows • per char.
     */
    fun display(raw: String, masked: Boolean): String {
        val chars = if (masked) "•".repeat(raw.length) else raw
        val grouped = chars.chunked(GROUP).joinToString("-")
        val trailing = raw.length in GROUP until RAW_LENGTH && raw.length % GROUP == 0
        return if (trailing) "$grouped-" else grouped
    }

    /** Cursor mapping raw → display; lands after the dash at group boundaries. */
    fun originalToTransformed(offset: Int): Int =
        offset + minOf(offset / GROUP, 3)

    /** Cursor mapping display → raw; dash positions map to the group boundary. */
    fun transformedToOriginal(offset: Int, rawLength: Int): Int =
        (offset - minOf(offset / (GROUP + 1), 3)).coerceIn(0, rawLength)
}
