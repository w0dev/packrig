package net.packset.data.adif

import net.packset.data.model.QsoContact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AdifWriter {
    private val createdTimestampFmt = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun export(
        contacts: List<QsoContact>,
        context: AdifExportContext = AdifExportContext(),
    ): String {
        if (contacts.isEmpty()) throw AdifExportException("No contacts to export")
        val created = createdTimestampFmt.format(Date())
        val headerFields = AdifNormalizer.normalizeHeader(context, created)
        val header = buildString {
            headerFields.forEach { (name, value) ->
                append(adifField(name, value))
            }
            append("<EOH>\n\n")
        }
        val records = contacts.joinToString(separator = "\n") { contact ->
            record(contact, context)
        }
        val adif = header + records
        AdifValidator.validateExport(adif)
        return adif
    }

    fun record(contact: QsoContact, context: AdifExportContext = AdifExportContext()): String {
        val fields = AdifNormalizer.normalizeRecord(contact, context)
        return buildString {
            fields.forEach { (name, value) ->
                append(adifField(name, value))
            }
            append("<EOR>\n")
        }
    }
}
