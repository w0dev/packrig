package net.packset.app.foundations.golden

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader

/**
 * Golden-trace data model + JSONL parser (FOUND-06).
 *
 * The schema is fixed and constrained — see
 * `docs/planning/field-sessions/RECORDING-FORMAT.md` (schema v1). This parser
 * deliberately does NOT depend on `kotlinx.serialization` or any other JSON
 * library: STACK.md keeps the production classpath free of JSON deps for this
 * milestone, and the schema is small enough that a hand-rolled parser is the
 * lightest workable option.
 *
 * The parser handles only the documented shape:
 *  - flat top-level object per line
 *  - integer `ts_ms`
 *  - string `kind`
 *  - optional `payload` object whose values are all strings (no nested objects,
 *    no arrays)
 *  - optional `decodes` array of flat decode objects (only on DECODE_BATCH)
 *  - blank lines and `//` comment lines are allowed
 *  - no escape sequences other than `\"` and `\\` inside strings
 */

/** One event kind in a recorded field session. */
enum class TraceEventKind {
    TRACE_START,
    DECODE_BATCH,
    UI_ACTION,
    CAT_READ,
    PTT_KEY_EXPECTED,
    PTT_RELEASE_EXPECTED,
    TX_SLOT,
    TRACE_END,
}

/** One decoded FT8 message embedded in a [TraceEventKind.DECODE_BATCH] event. */
data class TraceDecode(
    val message: String,
    val snr: Int,
    val dt: Float,
    val freqHz: Float,
    val score: Int,
)

/** One event from a JSONL trace. */
data class TraceEvent(
    val tsMs: Long,
    val kind: TraceEventKind,
    val payload: Map<String, String> = emptyMap(),
    val decodes: List<TraceDecode> = emptyList(),
)

/** Thrown when a JSONL line does not match the schema documented in RECORDING-FORMAT.md. */
class GoldenTraceParseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

object GoldenTrace {

    /** Parse JSONL from [reader]. Comment lines (`//…`) and blank lines are skipped. */
    fun loadJsonl(reader: Reader): List<TraceEvent> {
        val out = ArrayList<TraceEvent>()
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader)
        var lineNumber = 0
        buffered.useLines { lines ->
            for (raw in lines) {
                lineNumber++
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("//")) continue
                try {
                    out += parseLine(trimmed)
                } catch (t: Throwable) {
                    throw GoldenTraceParseException(
                        "Failed to parse line $lineNumber: ${t.message ?: t.javaClass.simpleName}\n  raw: $trimmed",
                        t,
                    )
                }
            }
        }
        return out
    }

    /** Convenience: load from a classpath resource (e.g. `traces/cq-answer-73.jsonl`). */
    fun loadFromClasspath(resourcePath: String): List<TraceEvent> {
        val cl = GoldenTrace::class.java.classLoader
            ?: error("No classloader available to load $resourcePath")
        val url = cl.getResource(resourcePath)
            ?: throw GoldenTraceParseException("Resource not found on classpath: $resourcePath")
        return url.openStream().bufferedReader().use { loadJsonl(it) }
    }

    /** Convenience: parse a single JSONL string (one or more lines). */
    fun loadString(jsonl: String): List<TraceEvent> = loadJsonl(StringReader(jsonl))

    // ----- Parser internals -----

    private fun parseLine(line: String): TraceEvent {
        require(line.startsWith("{") && line.endsWith("}")) {
            "Expected a top-level JSON object on the line; got: $line"
        }
        val body = line.substring(1, line.length - 1)
        val fields = splitTopLevel(body)
        var tsMs: Long? = null
        var kindStr: String? = null
        var payload: Map<String, String> = emptyMap()
        var decodes: List<TraceDecode> = emptyList()
        for ((key, rawValue) in fields) {
            when (key) {
                "ts_ms" -> tsMs = rawValue.trim().toLongOrError("ts_ms")
                "kind" -> kindStr = parseStringLiteral(rawValue.trim(), "kind")
                "payload" -> payload = parseStringMap(rawValue.trim())
                "decodes" -> decodes = parseDecodes(rawValue.trim())
                else -> throw GoldenTraceParseException("Unknown top-level field: $key")
            }
        }
        require(tsMs != null) { "Missing required field: ts_ms" }
        require(kindStr != null) { "Missing required field: kind" }
        val kind = try {
            TraceEventKind.valueOf(kindStr!!)
        } catch (_: IllegalArgumentException) {
            throw GoldenTraceParseException("Unknown kind: $kindStr")
        }
        if (decodes.isNotEmpty() && kind != TraceEventKind.DECODE_BATCH) {
            throw GoldenTraceParseException("decodes[] only allowed on DECODE_BATCH; got $kind")
        }
        return TraceEvent(tsMs = tsMs!!, kind = kind, payload = payload, decodes = decodes)
    }

    /** Split a JSON-object body by top-level commas (respecting strings + nested braces/brackets). */
    private fun splitTopLevel(body: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var depth = 0
        var inString = false
        var escaped = false
        var fieldStart = 0
        val len = body.length
        var i = 0
        while (i <= len) {
            val end = i == len
            val ch = if (end) ',' else body[i]
            if (!end && inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else if (!end) {
                when (ch) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
            if ((end || ch == ',') && depth == 0 && !inString) {
                val chunk = body.substring(fieldStart, i).trim()
                if (chunk.isNotEmpty()) {
                    val colonIdx = findTopLevelColon(chunk)
                    require(colonIdx > 0) { "Expected key:value pair, got: $chunk" }
                    val key = parseStringLiteral(chunk.substring(0, colonIdx).trim(), "key")
                    val value = chunk.substring(colonIdx + 1).trim()
                    out += key to value
                }
                fieldStart = i + 1
            }
            i++
        }
        return out
    }

    private fun findTopLevelColon(chunk: String): Int {
        var inString = false
        var escaped = false
        for (i in chunk.indices) {
            val ch = chunk[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                if (ch == '"') inString = true
                else if (ch == ':') return i
            }
        }
        return -1
    }

    private fun parseStringLiteral(raw: String, fieldName: String): String {
        require(raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            "Expected double-quoted string for $fieldName; got: $raw"
        }
        val inner = raw.substring(1, raw.length - 1)
        // Minimal unescaping: \" and \\ only (the only escapes the schema allows).
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                val next = inner[i + 1]
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> throw GoldenTraceParseException("Unsupported escape: \\$next")
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun parseStringMap(raw: String): Map<String, String> {
        if (raw == "{}") return emptyMap()
        require(raw.startsWith("{") && raw.endsWith("}")) {
            "Expected object for payload; got: $raw"
        }
        val inner = raw.substring(1, raw.length - 1)
        val out = LinkedHashMap<String, String>()
        for ((key, value) in splitTopLevel(inner)) {
            out[key] = parseStringLiteral(value.trim(), "payload[$key]")
        }
        return out
    }

    private fun parseDecodes(raw: String): List<TraceDecode> {
        if (raw == "[]") return emptyList()
        require(raw.startsWith("[") && raw.endsWith("]")) {
            "Expected array for decodes; got: $raw"
        }
        val inner = raw.substring(1, raw.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        val out = ArrayList<TraceDecode>()
        // Each element is a flat object {...}. Split on top-level commas.
        var depth = 0
        var inString = false
        var escaped = false
        var start = 0
        for (i in inner.indices) {
            val ch = inner[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0 && ch == '}') {
                        val obj = inner.substring(start, i + 1).trim()
                        out += parseDecodeObject(obj)
                    }
                }
                ',' -> if (depth == 0) start = i + 1
            }
            if (depth == 0 && (ch == ',' || ch == '}')) {
                // After a close-brace we want `start` to point at the next non-comma char.
                if (ch == '}' && i + 1 < inner.length) {
                    var j = i + 1
                    while (j < inner.length && (inner[j] == ',' || inner[j].isWhitespace())) j++
                    start = j
                }
            }
        }
        return out
    }

    private fun parseDecodeObject(raw: String): TraceDecode {
        require(raw.startsWith("{") && raw.endsWith("}")) {
            "Expected decode object; got: $raw"
        }
        val body = raw.substring(1, raw.length - 1)
        var message: String? = null
        var snr: Int? = null
        var dt: Float? = null
        var freqHz: Float? = null
        var score: Int? = null
        for ((key, value) in splitTopLevel(body)) {
            val trimmed = value.trim()
            when (key) {
                "message" -> message = parseStringLiteral(trimmed, "decode.message")
                "snr" -> snr = trimmed.toIntOrError("snr")
                "dt" -> dt = trimmed.toFloatOrError("dt")
                "freq_hz" -> freqHz = trimmed.toFloatOrError("freq_hz")
                "score" -> score = trimmed.toIntOrError("score")
                else -> throw GoldenTraceParseException("Unknown decode field: $key")
            }
        }
        return TraceDecode(
            message = message ?: error("decode.message required"),
            snr = snr ?: error("decode.snr required"),
            dt = dt ?: error("decode.dt required"),
            freqHz = freqHz ?: error("decode.freq_hz required"),
            score = score ?: error("decode.score required"),
        )
    }

    private fun String.toLongOrError(field: String): Long =
        toLongOrNull() ?: throw GoldenTraceParseException("Expected integer for $field; got: $this")

    private fun String.toIntOrError(field: String): Int =
        toIntOrNull() ?: throw GoldenTraceParseException("Expected integer for $field; got: $this")

    private fun String.toFloatOrError(field: String): Float =
        toFloatOrNull() ?: throw GoldenTraceParseException("Expected number for $field; got: $this")
}
