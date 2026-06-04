package net.ft8vc.data.adif

internal object AdifField {
    fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\r", "").replace("\n", " ")
}

internal fun adifField(name: String, value: String?): String? {
    if (value.isNullOrBlank()) return null
    val escaped = AdifField.escape(value)
    return "<$name:${escaped.length}>$escaped "
}
