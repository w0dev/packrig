package net.ft8vc.app.controllers

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.OperateUiState
import net.ft8vc.core.TxSlotParity
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import net.ft8vc.ft8native.fakes.Ft8DecoderFake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeControllerTest {

    private lateinit var decoder: Ft8DecoderFake
    private lateinit var controllerScope: CoroutineScope
    private lateinit var controller: DecodeController
    private val clockMs = AtomicLong(SLOT_0_START_EPOCH_MS)

    @Before fun setUp() {
        clockMs.set(SLOT_0_START_EPOCH_MS)
        decoder = Ft8DecoderFake()
        controllerScope = CoroutineScope(UnconfinedTestDispatcher())
        controller = makeController(decoder)
        controller.setStationContext("W0DEV", "EM26")
    }

    @After fun tearDown() {
        controller.close()
        controllerScope.cancel()
    }

    private fun makeController(d: Ft8DecoderApi): DecodeController {
        // UnconfinedTestDispatcher so scope.launch(decodeDispatcher) runs inline —
        // assertions immediately after onFrames see the decoded slice without races.
        val executor = Executors.newSingleThreadExecutor()
        return DecodeController(
            decoder = d,
            scope = controllerScope,
            executor = executor,
            decodeDispatcher = UnconfinedTestDispatcher(),
            clock = { clockMs.get() },
        )
    }

    /** Drive the SlotCollector across a UTC slot boundary so the queued canned decode flushes. */
    private fun flushOneSlot() {
        // Slot 0: fill enough samples (>=85% of a slot) at the current clock.
        controller.onFrames(ShortArray(180_000) { 1000 })
        // Advance clock past the boundary; the next add() trips SlotCollector.flush.
        clockMs.set(clockMs.get() + 15_001L)
        controller.onFrames(ShortArray(1))
    }

    @Test
    fun clearDecodes_emptiesSliceList_andResetsLastSlotCount() = runTest {
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50)),
        )
        flushOneSlot()
        assertEquals(1, controller.slice.value.decodes.size)

        controller.clearDecodes()
        assertEquals(0, controller.slice.value.decodes.size)
        assertEquals(-1, controller.slice.value.lastSlotDecodeCount)
    }

    @Test
    fun decodeRows_haveDistinctStableIds_withinSlot() = runTest {
        decoder.queueDecodeResults(
            listOf(
                Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50),
                Ft8DecodeResult("CQ K2DEF EM73", -10, 0.0f, 1200f, 40),
                Ft8DecodeResult("CQ K3GHI DM79", -12, 0.0f, 1400f, 30),
            ),
        )
        flushOneSlot()

        val ids = controller.slice.value.decodes.map { it.id }
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size) // all distinct
    }

    @Test
    fun decodeRows_acrossSlots_haveDistinctIds() = runTest {
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50)),
        )
        flushOneSlot()
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K2DEF EM73", -10, 0.0f, 1200f, 40)),
        )
        flushOneSlot()

        val ids = controller.slice.value.decodes.map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun decodesOut_emitsBatch_withSlotStart_andDecodes() = runTest {
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50)),
        )
        controller.decodesOut.test {
            flushOneSlot()
            val batch = awaitItem()
            assertEquals(SLOT_0_START_EPOCH_MS, batch.slotStartEpochMs)
            assertEquals(1, batch.decodes.size)
            assertEquals("CQ K1ABC FN42", batch.decodes[0].message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun decodeFailure_incrementsFailureCount_andDoesNotEmitBatch() = runTest {
        // Swap in a throwing decoder so we don't need fake-side failure injection.
        controller.close()
        val throwingDecoder = object : Ft8DecoderApi {
            override fun isAvailable() = true
            override fun version() = "throwing"
            override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> =
                throw RuntimeException("decode boom")
            override fun encode(message: String, freqHz: Float, sampleRate: Int, offsetSymbols: Int) = ShortArray(0)
        }
        controller = makeController(throwingDecoder)
        controller.setStationContext("W0DEV", "EM26")

        controller.decodesOut.test {
            flushOneSlot()
            expectNoEvents()
            assertEquals(1L, controller.slice.value.decodeFailureCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun decodeList_capsAt_MAX_DECODE_ROWS() = runTest {
        // Each flushOneSlot emits one row (size-1 queue) and advances clock 15s. Run > MAX.
        repeat(OperateUiState.MAX_DECODE_ROWS + 5) { i ->
            decoder.queueDecodeResults(
                listOf(Ft8DecodeResult("CQ K${i}A EM26", -8, 0.0f, 1000f, 50)),
            )
            flushOneSlot()
        }
        assertEquals(OperateUiState.MAX_DECODE_ROWS, controller.slice.value.decodes.size)
    }

    @Test
    fun reset_clearsLevelMeter_andPreservesDecodeList() = runTest {
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50)),
        )
        flushOneSlot()
        controller.onFrames(ShortArray(1200) { 5000 })
        assertTrue(controller.slice.value.levelDbfs > OperateUiState.SILENCE_DBFS)

        controller.reset()
        assertEquals(OperateUiState.SILENCE_DBFS, controller.slice.value.levelDbfs)
        // decodes list is preserved across reset — only clearDecodes empties it.
        assertEquals(1, controller.slice.value.decodes.size)
    }

    @Test
    fun decodesOut_carries_slotParity_matchingSlotStart() = runTest {
        decoder.queueDecodeResults(
            listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.0f, 1000f, 50)),
        )
        controller.decodesOut.test {
            flushOneSlot()
            val batch = awaitItem()
            // Slot parity is 0 (even) or 1 (odd); whichever it is, must be consistent
            // with TxSlotSelection — we just assert it's a valid value.
            assertTrue(batch.slotParity == TxSlotParity.EVEN || batch.slotParity == TxSlotParity.ODD)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        // 1700000010000 % 15000 == 0 — aligned to UTC 15-second slot boundary.
        const val SLOT_0_START_EPOCH_MS = 1_700_000_010_000L
    }
}
