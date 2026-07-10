package net.ft8vc.app.controllers

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodeControllerCountryTest {
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
    fun `new rows carry the iso country of the sender call`() = runTest {
        val fake = SingleShotFake(
            listOf(
                result("CQ JA1XYZ PM95", 1500.0, snr = -10),
                result("K1ABC DL1ABC -07", 1700.0, snr = -12),
            )
        )
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)

        val byMessage = controller.slice.value.decodes.associateBy { it.message }
        assertEquals("JP", byMessage.getValue("CQ JA1XYZ PM95").countryCode)
        assertEquals("DE", byMessage.getValue("K1ABC DL1ABC -07").countryCode)
        controller.close()
    }

    @Test
    fun `rows without a resolvable sender have null country`() = runTest {
        val fake = SingleShotFake(listOf(result("W2XYZ <PJ4/K1ABC> -05", 1500.0, snr = -10)))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)

        assertNull(controller.slice.value.decodes.single().countryCode)
        controller.close()
    }
}
