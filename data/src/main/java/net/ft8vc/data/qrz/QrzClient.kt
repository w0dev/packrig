package net.ft8vc.data.qrz

/** Result of one QRZ Logbook API call. Duplicate inserts count as [Success]. */
sealed interface QrzOutcome {
    data class Success(val callsign: String?, val count: Int?) : QrzOutcome
    data class Failure(val message: String) : QrzOutcome
}

/** QRZ Logbook API operations used by the upload queue and the Test button. */
interface QrzClient {
    suspend fun status(apiKey: String): QrzOutcome
    suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome
}

/** Map parsed response fields to an outcome (RESULT=OK, duplicate FAIL → Success). */
fun interpretResponse(fields: Map<String, String>): QrzOutcome {
    val reason = fields["REASON"]
    return when (fields["RESULT"]?.uppercase()) {
        "OK" -> QrzOutcome.Success(fields["CALLSIGN"], fields["COUNT"]?.toIntOrNull())
        "FAIL", "AUTH" ->
            if (QrzWire.isDuplicateReason(reason)) QrzOutcome.Success(null, null)
            else QrzOutcome.Failure(reason ?: "QRZ rejected the request")
        else -> QrzOutcome.Failure("Unrecognized response from QRZ")
    }
}
