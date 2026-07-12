package net.packset.data.qrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrzOutcomeTest {

    @Test
    fun ok_isSuccessWithStatusFields() {
        val outcome = interpretResponse(mapOf("RESULT" to "OK", "CALLSIGN" to "W0DEV", "COUNT" to "42"))
        assertEquals(QrzOutcome.Success(callsign = "W0DEV", count = 42), outcome)
    }

    @Test
    fun ok_withoutStatusFieldsIsSuccess() {
        assertEquals(QrzOutcome.Success(null, null), interpretResponse(mapOf("RESULT" to "OK", "LOGID" to "9")))
    }

    @Test
    fun duplicateFail_isSuccess() {
        val outcome = interpretResponse(
            mapOf("RESULT" to "FAIL", "REASON" to "Unable to add QSO to database: duplicate"),
        )
        assertTrue(outcome is QrzOutcome.Success)
    }

    @Test
    fun authFailure_isFailureWithReason() {
        val outcome = interpretResponse(mapOf("RESULT" to "AUTH", "REASON" to "invalid api key"))
        assertEquals(QrzOutcome.Failure("invalid api key"), outcome)
    }

    @Test
    fun fail_withoutReasonGetsGenericMessage() {
        assertEquals(QrzOutcome.Failure("QRZ rejected the request"), interpretResponse(mapOf("RESULT" to "FAIL")))
    }

    @Test
    fun missingResult_isFailure() {
        assertEquals(QrzOutcome.Failure("Unrecognized response from QRZ"), interpretResponse(emptyMap()))
    }

    @Test
    fun nonNumericCount_isNull() {
        val outcome = interpretResponse(mapOf("RESULT" to "OK", "COUNT" to "n/a"))
        assertEquals(QrzOutcome.Success(null, null), outcome)
    }
}
