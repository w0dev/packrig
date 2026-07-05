package net.ft8vc.app.ui.spectrum

import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodeRowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumMarkersTest {

    /** id = slotStart * 1000 + index, matching DecodeRowKey.stableId. */
    private fun row(
        slotStart: Long,
        index: Int,
        freqHz: Int,
        message: String,
        isCq: Boolean,
        source: DecodeRowSource = DecodeRowSource.Rx,
    ) = DecodeRow(
        id = slotStart * 1000 + index,
        timeUtc = "000000",
        snr = 0,
        dtSeconds = 0f,
        freqHz = freqHz,
        message = message,
        isCq = isCq,
        source = source,
    )

    @Test
    fun emptyDecodesYieldNoMarkers() {
        assertTrue(SpectrumMarkers.forLatestSlot(emptyList()).isEmpty())
    }

    @Test
    fun onlyLatestSlotContributesMarkers() {
        val decodes = listOf(
            row(200L, 0, 1500, "CQ K1ABC FN42", isCq = true),   // latest slot
            row(100L, 0, 1200, "CQ W2XYZ EM12", isCq = true),   // older slot
        )
        val markers = SpectrumMarkers.forLatestSlot(decodes)
        assertEquals(1, markers.size)
        assertEquals(1500, markers[0].freqHz)
        assertEquals("K1ABC", markers[0].callsign)
        assertTrue(markers[0].isCq)
    }

    @Test
    fun nonCqSenderParsedForCollisionMarker() {
        val markers = SpectrumMarkers.forLatestSlot(
            listOf(row(200L, 0, 1800, "K1ABC W2XYZ -12", isCq = false)),
        )
        assertEquals(1, markers.size)
        assertEquals("W2XYZ", markers[0].callsign)
        assertFalse(markers[0].isCq)
    }

    @Test
    fun syntheticTxRowsExcluded() {
        val markers = SpectrumMarkers.forLatestSlot(
            listOf(row(200L, 0, 1500, "CQ K1ABC FN42", isCq = true, source = DecodeRowSource.Tx)),
        )
        assertTrue(markers.isEmpty())
    }

    @Test
    fun txClashesWhenWithinWindow() {
        val markers = listOf(SpectrumMarker(1500, "K1ABC", true))
        assertTrue(SpectrumMarkers.txClashes(1520, markers))   // 20 Hz apart
        assertTrue(SpectrumMarkers.txClashes(1500, markers))   // exact
    }

    @Test
    fun noClashJustOutsideWindow() {
        val markers = listOf(SpectrumMarker(1500, "K1ABC", true))
        assertFalse(SpectrumMarkers.txClashes(1531, markers))  // 31 Hz apart, window 30
    }

    @Test
    fun noClashWithNoMarkers() {
        assertFalse(SpectrumMarkers.txClashes(1500, emptyList()))
    }
}
