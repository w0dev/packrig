package net.packrig.data.qrz

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * QRZ Logbook API wire format: `&`-delimited KEY=VALUE pairs in both
 * directions (https://logbook.qrz.com/api). Pure functions, JVM-testable.
 */
object QrzWire {

    /** Parse a response body into uppercase-keyed, URL-decoded fields. */
    fun parse(body: String): Map<String, String> =
        body.split('&').mapNotNull { piece ->
            val eq = piece.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = piece.substring(0, eq).trim().uppercase()
            val value = try {
                URLDecoder.decode(piece.substring(eq + 1), Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                piece.substring(eq + 1)
            }
            key to value
        }.toMap()

    /** Encode request params as a form body; values URL-encoded UTF-8. */
    fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
        }

    /** QRZ rejects re-inserts with a REASON containing "duplicate". */
    fun isDuplicateReason(reason: String?): Boolean =
        reason?.contains("duplicate", ignoreCase = true) == true
}
