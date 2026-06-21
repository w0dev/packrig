package net.ft8vc.core

/** A decoded message paired with its SNR, fed into the QSO machine. */
data class QsoDecode(val message: String, val snr: Int)

/**
 * A parsed FT8 message relevant to QSO sequencing.
 *
 * Directed messages carry [target] (token 0, the station being called) and
 * [sender] (token 1). The QSO machine compares these against my/DX callsigns.
 */
sealed interface QsoRx {
    /** `CQ {call} {grid}` (or `CQ {modifier} {call} {grid}`). Not directed at anyone. */
    data class Cq(val call: String, val grid: String?) : QsoRx

    /** `{target} {sender} {grid}` — a reply carrying a 4-char Maidenhead locator. */
    data class GridReply(val target: String, val sender: String, val grid: String) : QsoRx

    /** `{target} {sender} {report}` — a signal report like `-07`. */
    data class Report(val target: String, val sender: String, val snr: Int) : QsoRx

    /** `{target} {sender} R{report}` — roger + report, like `R-07`. */
    data class RReport(val target: String, val sender: String, val snr: Int) : QsoRx

    /** `{target} {sender} RRR`. */
    data class Roger(val target: String, val sender: String) : QsoRx

    /** `{target} {sender} RR73` (roger + 73 combined). */
    data class RogerBye(val target: String, val sender: String) : QsoRx

    /** `{target} {sender} 73`. */
    data class Bye(val target: String, val sender: String) : QsoRx

    /** Anything not recognized as part of a QSO. */
    data object Other : QsoRx
}

/**
 * Formats and parses the standard FT8 QSO message set.
 *
 * Convention for directed messages: `{to} {from} {payload}` (e.g.
 * `K1ABC W0DEV -05` is to K1ABC, from W0DEV, reporting -5 dB).
 */
object QsoMessages {

    private val GRID = Regex("^[A-R]{2}[0-9]{2}$")
    private val REPORT = Regex("^[+-][0-9]{2}$")
    private val R_REPORT = Regex("^R[+-][0-9]{2}$")

    /** FT8 signal report: signed, zero-padded to two digits (e.g. `-05`, `+13`). */
    fun formatReport(snr: Int): String {
        val clamped = snr.coerceIn(-30, 49)
        return "%+03d".format(clamped)
    }

    fun cq(myCall: String, myGrid: String, modifier: String? = null): String =
        if (modifier.isNullOrBlank()) "CQ $myCall $myGrid" else "CQ $modifier $myCall $myGrid"

    /** ADIF RST field for FT8 (3-char signed report, e.g. `-05`, `+13`). */
    fun formatAdifRst(snr: Int): String = formatReport(snr)

    fun reply(dxCall: String, myCall: String, myGrid: String): String = "$dxCall $myCall $myGrid"

    fun report(dxCall: String, myCall: String, snr: Int): String =
        "$dxCall $myCall ${formatReport(snr)}"

    fun rReport(dxCall: String, myCall: String, snr: Int): String =
        "$dxCall $myCall R${formatReport(snr)}"

    fun rrr(dxCall: String, myCall: String): String = "$dxCall $myCall RRR"

    fun rr73(dxCall: String, myCall: String): String = "$dxCall $myCall RR73"

    fun bye73(dxCall: String, myCall: String): String = "$dxCall $myCall 73"

    /** Parse a decoded message into a [QsoRx]. Returns [QsoRx.Other] if unrecognized. */
    fun parse(message: String): QsoRx {
        val tokens = message.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return QsoRx.Other

        if (tokens[0] == "CQ") return parseCq(tokens)
        if (tokens.size < 3) return QsoRx.Other

        val target = tokens[0]
        val sender = tokens[1]
        val payload = tokens[2]
        return when {
            R_REPORT.matches(payload) -> QsoRx.RReport(target, sender, payload.drop(1).toInt())
            REPORT.matches(payload) -> QsoRx.Report(target, sender, payload.toInt())
            payload == "RRR" -> QsoRx.Roger(target, sender)
            payload == "RR73" -> QsoRx.RogerBye(target, sender)
            payload == "73" -> QsoRx.Bye(target, sender)
            GRID.matches(payload) -> QsoRx.GridReply(target, sender, payload)
            else -> QsoRx.Other
        }
    }

    private fun parseCq(tokens: List<String>): QsoRx {
        // CQ {call} {grid}  or  CQ {modifier} {call} {grid}  or  CQ {call}
        val rest = tokens.drop(1)
        if (rest.isEmpty()) return QsoRx.Other
        // A callsign contains at least one digit; a modifier (DX/POTA/NA) does not.
        val callIdx = rest.indexOfFirst { it.any(Char::isDigit) && !GRID.matches(it) }
        if (callIdx < 0) return QsoRx.Other
        val call = rest[callIdx]
        val grid = rest.getOrNull(callIdx + 1)?.takeIf { GRID.matches(it) }
        return QsoRx.Cq(call, grid)
    }

    private val WHITESPACE = Regex("\\s+")
}
