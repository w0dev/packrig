package net.ft8vc.app.controllers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeControllerEarlyDedupTest {
    private val slotStart = 1_700_000_000_000L

    private fun result(message: String, freqHz: Double, snr: Int, dt: Float = 0f) =
        Ft8DecodeResult(message = message, snr = snr, dtSeconds = dt, freqHz = freqHz.toFloat(), score = snr)

    // Sequentially returns the queued result lists, one per decode call.
    private class QueuedFake(private val queue: ArrayDeque<List<Ft8DecodeResult>>) : Ft8DecoderApi {
        override fun isAvailable(): Boolean = true
        override fun version(): String = "queued-fake"
        override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
            if (queue.isNotEmpty()) queue.removeFirst().toTypedArray() else emptyArray()
        override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int): ShortArray =
            ShortArray(0)
    }

    @Test
    fun `early then full pass dedup union not sum`() = runTest {
        val earlyResults = listOf(result("CQ K1ABC FN42", 1500.0, snr = -10))
        val fullResults  = listOf(
            result("CQ K1ABC FN42", 1500.4, snr = -8),       // same as early (within 6.25 Hz bin)
            result("CQ W2DEF EM12", 1700.0, snr = -12),      // new
        )
        val fake = QueuedFake(ArrayDeque(listOf(earlyResults, fullResults)))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        val emitted = mutableListOf<DecodeBatch>()
        val collectJob = scope.launch { controller.decodesOut.toList(emitted) }

        controller.decodeSlot(ShortArray(115_200), slotStart, source = DecodePassSource.Early)
        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)
        scope.advanceUntilIdle()

        // Union, not sum: 2 unique decodes, not 3
        assertEquals(2, controller.slice.value.decodes.size)
        // Each unique key emitted at most once across both batches
        val allMessages = emitted.flatMap { it.decodes.map { d -> d.message } }
        assertEquals(2, allMessages.distinct().size)
        assertEquals(allMessages.size, allMessages.distinct().size)

        // Full pass updated the early row in place with the higher SNR
        val updated = controller.slice.value.decodes.first { it.message == "CQ K1ABC FN42" }
        assertEquals(-8, updated.snr)

        collectJob.cancel()
        controller.close()
    }

    @Test
    fun `duration instrumentation updates after each pass`() = runTest {
        val fake = QueuedFake(ArrayDeque(listOf(emptyList(), emptyList())))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(115_200), slotStart, source = DecodePassSource.Early)
        scope.advanceUntilIdle()
        assertEquals(DecodePassSource.Early, controller.slice.value.lastDecodePassSource)
        // duration is wall-clock; just assert non-negative + the field is wired
        assert(controller.slice.value.lastDecodePassDurationMs >= 0)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)
        scope.advanceUntilIdle()
        assertEquals(DecodePassSource.Full, controller.slice.value.lastDecodePassSource)

        controller.close()
    }
}
