package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AdifWriter {
    private val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val timeFmt = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun export(contacts: List<QsoContact>): String {
        val header = buildString {
            append(adifField("programid", "FT8VC"))
            append(adifField("created_timestamp", dateFmt.format(Date()) + " " + timeFmt.format(Date())))
            append("<EOH>\n\n")
        }
        return header + contacts.joinToString(separator = "\n") { record(it) }
    }

    fun record(contact: QsoContact): String {
        val date = Date(contact.utcMillis)
        return buildString {
            append(adifField("QSO_DATE", dateFmt.format(date)))
            append(adifField("TIME_ON", timeFmt.format(date)))
            append(adifField("CALL", contact.dxCall))
            append(adifField("BAND", contact.band))
            append(adifField("MODE", contact.mode))
            append(adifField("SUBMODE", "FT8"))
            contact.freqHz?.let { append(adifField("FREQ", "%.6f".format(it / 1_000_000.0))) }
            append(adifField("GRIDSQUARE", contact.dxGrid))
            append(adifField("STATION_CALLSIGN", contact.myCall))
            append(adifField("MY_GRIDSQUARE", contact.myGrid))
            contact.rstSent?.let { append(adifField("RST_SENT", it.toString())) }
            contact.rstRcvd?.let { append(adifField("RST_RCVD", it.toString())) }
            append(adifField("NOTES", contact.notes))
            append("<EOR>\n")
        }
    }
}
