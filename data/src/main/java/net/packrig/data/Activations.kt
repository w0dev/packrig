package net.packrig.data

import net.packrig.core.ActivationProfile
import net.packrig.data.model.QsoContact
import java.util.Calendar
import java.util.TimeZone

/** One POTA upload unit: every logged QSO at [parkRef] during UTC day [utcDate] (yyyyMMdd). */
data class Activation(
    val parkRef: String,
    val utcDate: String,
    val qsoCount: Int,
)

/** Pure grouping/filtering/naming for POTA activation exports. */
object Activations {

    fun utcDateOf(utcMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = utcMillis
        return "%04d%02d%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Derived activation groups, newest day first. A two-fer QSO appears under each of its parks. */
    fun groupActivations(contacts: List<QsoContact>): List<Activation> =
        contacts
            .flatMap { c ->
                ActivationProfile.parseParkRefs(c.potaParkRefs)
                    .map { park -> park to utcDateOf(c.utcMillis) }
            }
            .groupingBy { it }
            .eachCount()
            .map { (key, count) -> Activation(parkRef = key.first, utcDate = key.second, qsoCount = count) }
            .sortedWith(compareByDescending<Activation> { it.utcDate }.thenBy { it.parkRef })

    /** The QSOs belonging to one activation. */
    fun contactsFor(contacts: List<QsoContact>, parkRef: String, utcDate: String): List<QsoContact> =
        contacts.filter { c ->
            utcDateOf(c.utcMillis) == utcDate &&
                ActivationProfile.parseParkRefs(c.potaParkRefs).contains(parkRef)
        }

    /** Upload filename `CALL@PARK-YYYYMMDD.adi`; unsafe callsign chars become '-'. */
    fun fileName(myCall: String, parkRef: String, utcDate: String): String {
        val safeCall = myCall.uppercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        return "$safeCall@$parkRef-$utcDate.adi"
    }
}
