package net.packrig.data.adif

/** Validates ADI syntax and required QSO fields after export. */
object AdifValidator {
    private val FIELD_TAG = Regex("<([A-Za-z0-9_]+):(\\d+)(?::[A-Za-z])?>")
    private val REQUIRED_QSO_FIELDS = setOf("QSO_DATE", "TIME_ON", "CALL", "MODE")
    private val VALID_MODES = setOf("FT8")

    fun validateExport(adif: String) {
        val eohIndex = adif.indexOf("<EOH>", ignoreCase = true)
        if (eohIndex < 0) throw AdifExportException("ADIF export missing <EOH>")
        val body = adif.substring(eohIndex + 5)
        val records = splitRecords(body)
        if (records.isEmpty()) throw AdifExportException("ADIF export contains no QSO records")
        records.forEachIndexed { index, record ->
            validateRecord(record, index + 1)
        }
    }

    private fun splitRecords(body: String): List<String> {
        val parts = body.split(Regex("<EOR>", RegexOption.IGNORE_CASE))
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun validateRecord(record: String, recordNumber: Int) {
        val fields = parseFields(record)
        val missing = REQUIRED_QSO_FIELDS - fields.keys
        if (missing.isNotEmpty()) {
            throw AdifExportException("Record $recordNumber missing fields: ${missing.joinToString()}")
        }
        if (!fields.containsKey("BAND") && !fields.containsKey("FREQ")) {
            throw AdifExportException("Record $recordNumber requires BAND or FREQ")
        }
        fields.forEach { (name, spec) ->
            validateField(name, spec.first, spec.second, recordNumber)
        }
    }

    private fun validateField(name: String, declaredLen: Int, data: String, recordNumber: Int) {
        if (data.length != declaredLen) {
            throw AdifExportException(
                "Record $recordNumber field $name length mismatch: declared $declaredLen, actual ${data.length}",
            )
        }
        when (name) {
            "QSO_DATE" -> if (!AdifNormalizer.isValidQsoDate(data)) {
                throw AdifExportException("Record $recordNumber invalid QSO_DATE: $data")
            }
            "TIME_ON" -> if (!AdifNormalizer.isValidTimeOn(data)) {
                throw AdifExportException("Record $recordNumber invalid TIME_ON: $data")
            }
            "MODE" -> if (data.uppercase() !in VALID_MODES) {
                throw AdifExportException("Record $recordNumber invalid MODE: $data")
            }
            "BAND" -> if (!AdifNormalizer.isValidBand(data)) {
                throw AdifExportException("Record $recordNumber invalid BAND: $data")
            }
            "SUBMODE" -> throw AdifExportException("Record $recordNumber must not include SUBMODE for FT8")
        }
    }

    private fun parseFields(record: String): Map<String, Pair<Int, String>> {
        val result = linkedMapOf<String, Pair<Int, String>>()
        var searchFrom = 0
        while (searchFrom < record.length) {
            val match = FIELD_TAG.find(record, searchFrom) ?: break
            val fieldName = match.groupValues[1].uppercase()
            val length = match.groupValues[2].toInt()
            val dataStart = match.range.last + 1
            val dataEnd = (dataStart + length).coerceAtMost(record.length)
            val data = record.substring(dataStart, dataEnd)
            result[fieldName] = length to data
            searchFrom = dataEnd
        }
        return result
    }
}
