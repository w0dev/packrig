package net.packset.app.controllers

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.packset.core.DecodePassSource
import net.packset.core.WorkedBefore
import net.packset.ft8native.Ft8DecodeResult
import net.packset.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeControllerWorkedBeforeTest {
    private val slotStart = 1_700_000_000_000L

    private fun result(message: String, freqHz: Double, snr: Int) =
        Ft8DecodeResult(message = message, snr = snr, dtSeconds = 0f, freqHz = freqHz.toFloat(), score = snr)

    private class SingleShotFake(private val results: List<Ft8DecodeResult>) : Ft8DecoderApi {
        override fun isAvailable(): Boolean = true
        override fun version(): String = "single-shot-fake"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
            results.toTypedArray()
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray =
            ShortArray(0)
    }

    @Test
    fun `reclassify updates existing rows for the logged call only`() = runTest {
        // Field bug 2026-07-04: rows already on screen keep WorkedBefore.Never
        // after the QSO logs — workedBefore is stamped once at decode time and
        // never refreshed, so the operator sees no worked-before indication on
        // the station they just worked.
        val workedByCall = mutableMapOf<String, WorkedBefore>()
        val fake = SingleShotFake(
            listOf(
                result("CQ K1ABC FN42", 1500.0, snr = -10),
                result("CQ W2DEF EM12", 1700.0, snr = -12),
            )
        )
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            workedBeforeLookup = { call -> workedByCall[call] ?: WorkedBefore.Never },
        )

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)
        assertEquals(
            "both rows classify Never before the QSO logs",
            listOf(WorkedBefore.Never, WorkedBefore.Never),
            controller.slice.value.decodes.map { it.workedBefore },
        )

        // QSO with K1ABC completes and logs; the logbook now reports this band worked.
        workedByCall["K1ABC"] = WorkedBefore.ThisBand
        controller.reclassifyWorkedBefore("K1ABC")

        val byMessage = controller.slice.value.decodes.associateBy { it.message }
        assertEquals(WorkedBefore.ThisBand, byMessage.getValue("CQ K1ABC FN42").workedBefore)
        assertEquals(WorkedBefore.Never, byMessage.getValue("CQ W2DEF EM12").workedBefore)

        controller.close()
    }

    @Test
    fun `reclassify matches hashed sender rows against the logged full call`() = runTest {
        // A nonstandard call appears hashed (<PJ4/K1ABC>) as the sender of
        // directed messages while the logbook stores the full call from its CQ.
        val workedByCall = mutableMapOf<String, WorkedBefore>()
        val fake = SingleShotFake(listOf(result("W2XYZ <PJ4/K1ABC> -05", 1500.0, snr = -10)))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            workedBeforeLookup = { call -> workedByCall[call] ?: WorkedBefore.Never },
        )

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)

        workedByCall["PJ4/K1ABC"] = WorkedBefore.ThisBand
        controller.reclassifyWorkedBefore("PJ4/K1ABC")

        assertEquals(
            WorkedBefore.ThisBand,
            controller.slice.value.decodes.single().workedBefore,
        )
        controller.close()
    }

    @Test
    fun `reclassify is a no-op for a call with no rows`() = runTest {
        val fake = SingleShotFake(listOf(result("CQ K1ABC FN42", 1500.0, snr = -10)))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)
        val before = controller.slice.value.decodes

        controller.reclassifyWorkedBefore("N0CALL")

        assertEquals(before, controller.slice.value.decodes)
        controller.close()
    }
}
