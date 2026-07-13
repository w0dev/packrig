package net.packrig.data.adif

/** Strip characters that break ADI field parsing; collapse line breaks to spaces. */
internal object AdifSanitizer {
    fun sanitize(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '\r', '\n' -> append(' ')
                '<', '>' -> Unit
                else -> append(c)
            }
        }
    }.trim()
}

internal fun adifField(name: String, value: String?): String? {
    if (value.isNullOrBlank()) return null
    val sanitized = AdifSanitizer.sanitize(value)
    if (sanitized.isEmpty()) return null
    return "<$name:${sanitized.length}>$sanitized "
}
