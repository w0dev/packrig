package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.fakes.Ft8DecoderFake
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeControllerClockRealignTest {

    // A known slot-boundary-aligned epoch (matches the sibling DecodeControllerTest constant).
    private val slot0Start = 1_700_000_000_000L
    private val clockMs = AtomicLong(slot0Start)
    private lateinit var decoder: Ft8DecoderFake
    private lateinit var scope: CoroutineScope
    private lateinit var controller: DecodeController

    @Before fun setUp() {
        clockMs.set(slot0Start)
        decoder = Ft8DecoderFake()
        scope = CoroutineScope(UnconfinedTestDispatcher())
        controller = DecodeController(
            decoder = decoder,
            scope = scope,
            executor = Executors.newSingleThreadExecutor(),
            decodeDispatcher = UnconfinedTestDispatcher(),
            clock = { clockMs.get() },
        )
        controller.setStationContext("W0DEV", "EM26")
        controller.setEarlyDecodeEnabled(false)   // keep the test to the FULL boundary pass
    }

    @After fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test fun `realign clears the published clock offset`() = runTest {
        // Four DTs in one FULL slot -> pooled >= MIN_SAMPLES -> non-null offset.
        decoder.queueDecodeResults(
            listOf(
                Ft8DecodeResult("CQ K1ABC FN42", -8, 2.0f, 1000f, 50),
                Ft8DecodeResult("CQ K2DEF FN30", -8, 2.0f, 1100f, 50),
                Ft8DecodeResult("CQ K3GHI FN20", -8, 2.0f, 1200f, 50),
                Ft8DecodeResult("CQ K4JKL FN10", -8, 2.0f, 1300f, 50),
            ),
        )
        // Fill slot 0 then cross the boundary to trigger the FULL decode pass.
        controller.onFrames(ShortArray(180_000) { 1000 })
        clockMs.set(clockMs.get() + 15_001L)
        controller.onFrames(ShortArray(1))

        assertNotNull(controller.slice.value.clockOffsetSeconds)

        controller.realignClockEstimate()

        assertNull(controller.slice.value.clockOffsetSeconds)
    }
}
