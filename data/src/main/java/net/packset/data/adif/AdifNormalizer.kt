package net.packset.data.adif

import net.packset.core.ActivationProfile
import net.packset.core.QsoMessages
import net.packset.data.model.QsoContact

/** Maps domain contacts to canonical ADIF field values before write. */
object AdifNormalizer {
    private val GRID = Regex("^[A-R]{2}[0-9]{2}$", RegexOption.IGNORE_CASE)
    private val QSO_DATE = Regex("^\\d{8}$")
    private val TIME_ON = Regex("^\\d{6}$")
    private val VALID_BANDS = setOf(
        "2190m", "630m", "560m", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m",
        "12m", "10m", "8m", "6m", "5m", "4m", "2m", "1.25m", "70cm", "33cm", "23cm", "13cm",
        "9cm", "6cm", "3cm", "1.25cm", "6mm", "4mm", "2.5mm", "2mm", "1mm", "submm",
    )

    fun normalizeRecord(contact: QsoContact, context: AdifExportContext): LinkedHashMap<String, String> {
        val fields = linkedMapOf<String, String>()
        val qsoDate = formatQsoDate(contact.utcMillis)
        val timeOn = formatTimeOn(contact.utcMillis)
        fields["QSO_DATE"] = qsoDate
        fields["TIME_ON"] = timeOn
        fields["CALL"] = requireField("CALL", contact.dxCall)
        fields["MODE"] = "FT8"
        contact.band?.let { band ->
            val normalized = band.trim().lowercase()
            require(normalized in VALID_BANDS) { "Invalid BAND: $band" }
            fields["BAND"] = normalized
        }
        contact.freqHz?.let { hz ->
            fields["FREQ"] = "%.6f".format(hz / 1_000_000.0)
        }
        if (!fields.containsKey("BAND") && !fields.containsKey("FREQ")) {
            throw AdifExportException("QSO with ${contact.dxCall} missing BAND and FREQ")
        }
        contact.dxGrid?.let { grid ->
            val g = grid.trim().uppercase()
            if (GRID.matches(g)) fields["GRIDSQUARE"] = g
        }
        fields["STATION_CALLSIGN"] = requireField("STATION_CALLSIGN", contact.myCall)
        val myGrid = contact.myGrid.trim().uppercase()
        if (GRID.matches(myGrid)) fields["MY_GRIDSQUARE"] = myGrid
        contact.rstSent?.let { fields["RST_SENT"] = QsoMessages.formatAdifRst(it) }
        contact.rstRcvd?.let { fields["RST_RCVD"] = QsoMessages.formatAdifRst(it) }
        if (contact.notes.isNotBlank()) fields["NOTES"] = contact.notes
        val parkForRecord = context.activationParkRef
            ?.let {
                ActivationProfile.normalizeParkRef(it)
                    ?: throw AdifExportException("Invalid activation park ref: $it")
            }
            ?: contact.potaParkRefs
        if (parkForRecord != null) {
            fields["MY_SIG"] = ActivationProfile.POTA_SIG
            fields["MY_SIG_INFO"] = parkForRecord
        }
        return fields
    }

    fun normalizeHeader(context: AdifExportContext, createdTimestamp: String): LinkedHashMap<String, String> =
        linkedMapOf(
            "ADIF_VER" to context.adifVersion,
            "PROGRAMID" to context.programId,
            "PROGRAMVERSION" to context.programVersion,
            "CREATED_TIMESTAMP" to createdTimestamp,
        )

    private fun requireField(name: String, value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw AdifExportException("Missing required field: $name")
        return trimmed
    }

    private fun formatQsoDate(utcMillis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = utcMillis
        return "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    private fun formatTimeOn(utcMillis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = utcMillis
        return "%02d%02d%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    internal fun isValidQsoDate(value: String): Boolean = QSO_DATE.matches(value)
    internal fun isValidTimeOn(value: String): Boolean = TIME_ON.matches(value)
    internal fun isValidBand(value: String): Boolean = value.lowercase() in VALID_BANDS
}
