package net.packrig.data.adif

import net.packrig.data.model.QsoContact
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToLong

class AdifImportException(message: String) : Exception(message)

data class AdifReadResult(val contacts: List<QsoContact>, val skipped: Int)

/**
 * Parses ADIF (.adi) text into [QsoContact]s. Tolerant by design: tag names are
 * case-insensitive, unknown fields are ignored, and records missing a CALL or a
 * parseable QSO_DATE+TIME_ON are skipped and counted rather than failing the file.
 * [fallbackMyCall]/[fallbackMyGrid] fill STATION_CALLSIGN / MY_GRIDSQUARE when the
 * file lacks them (foreign logger exports).
 */
object AdifReader {

    private val FIELD = Regex("<([A-Za-z0-9_]+)(?::(\\d+))?(?::[^>]*)?>")

    fun read(
        text: String,
        fallbackMyCall: String = "",
        fallbackMyGrid: String = "",
    ): AdifReadResult {
        val contacts = mutableListOf<QsoContact>()
        var skipped = 0
        var fields = mutableMapOf<String, String>()
        // Everything before <EOH> is header; files without a header start in records.
        var inHeader = text.contains("<eoh>", ignoreCase = true)
        var i = 0
        while (i < text.length) {
            val m = FIELD.find(text, i) ?: break
            val name = m.groupValues[1].lowercase()
            val len = m.groupValues[2].toIntOrNull()
            i = m.range.last + 1
            when {
                name == "eoh" -> {
                    inHeader = false
                    fields = mutableMapOf()
                }
                name == "eor" -> {
                    if (!inHeader && fields.isNotEmpty()) {
                        val contact = toContact(fields, fallbackMyCall, fallbackMyGrid)
                        if (contact != null) contacts += contact else skipped++
                    }
                    fields = mutableMapOf()
                }
                len != null -> {
                    val end = (i + len).coerceAtMost(text.length)
                    fields[name] = text.substring(i, end)
                    i = end
                }
            }
        }
        if (contacts.isEmpty()) {
            throw AdifImportException(
                if (skipped == 0) "No ADIF records found"
                else "No usable QSO records in file ($skipped unreadable)",
            )
        }
        return AdifReadResult(contacts, skipped)
    }

    private fun toContact(
        f: Map<String, String>,
        fallbackMyCall: String,
        fallbackMyGrid: String,
    ): QsoContact? {
        val call = f["call"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val utcMillis = parseUtcMillis(f["qso_date"], f["time_on"]) ?: return null
        return QsoContact(
            utcMillis = utcMillis,
            myCall = f["station_callsign"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: f["operator"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: fallbackMyCall,
            myGrid = f["my_gridsquare"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: fallbackMyGrid,
            dxCall = call,
            dxGrid = f["gridsquare"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            rstSent = f["rst_sent"]?.trim()?.toIntOrNull(),
            rstRcvd = f["rst_rcvd"]?.trim()?.toIntOrNull(),
            freqHz = f["freq"]?.trim()?.toDoubleOrNull()?.let { (it * 1_000_000).roundToLong() },
            mode = f["mode"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "FT8",
            band = f["band"]?.trim()?.lowercase()?.takeIf { AdifNormalizer.isValidBand(it) },
            notes = f["notes"]?.trim() ?: f["comment"]?.trim() ?: "",
            potaParkRefs = f["my_sig_info"]?.trim()?.takeIf {
                it.isNotEmpty() && f["my_sig"]?.trim().equals("POTA", ignoreCase = true)
            } ?: f["pota_ref"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** yyyyMMdd + HHmmss (or HHmm) → UTC epoch millis; null when unparseable. */
    private fun parseUtcMillis(date: String?, time: String?): Long? {
        val d = date?.trim() ?: return null
        val t = time?.trim() ?: return null
        if (d.length != 8 || d.any { !it.isDigit() }) return null
        if ((t.length != 6 && t.length != 4) || t.any { !it.isDigit() }) return null
        val month = d.substring(4, 6).toInt()
        val day = d.substring(6, 8).toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(
            d.substring(0, 4).toInt(), month - 1, day,
            t.substring(0, 2).toInt(), t.substring(2, 4).toInt(),
            if (t.length == 6) t.substring(4, 6).toInt() else 0,
        )
        return cal.timeInMillis
    }
}
