package net.ft8vc.app.ui.spectrum

import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodeRowSource
import net.ft8vc.core.QsoMessages
import net.ft8vc.core.QsoRx
import kotlin.math.abs

/** One decoded signal's position on the waterfall. [callsign] is "" when unparsable. */
data class SpectrumMarker(val freqHz: Int, val callsign: String, val isCq: Boolean)

/**
 * Derives waterfall markers from the decode rows the Spectrum screen already
 * receives in [net.ft8vc.app.OperateUiState.decodes]. Pure — no Android deps.
 *
 * Only the most recent slot's RX decodes contribute, so markers refresh in place
 * every 15 s and nothing accumulates. CQ markers drive on-screen labels; all
 * markers (CQ and directed) contribute occupied frequencies for [txClashes].
 */
object SpectrumMarkers {
    /** Center-to-center Hz within which the TX tone is treated as clashing. Field-tunable. */
    const val CLASH_WINDOW_HZ: Int = 30

    fun forLatestSlot(decodes: List<DecodeRow>): List<SpectrumMarker> {
        val rx = decodes.filter { it.source == DecodeRowSource.Rx }
        if (rx.isEmpty()) return emptyList()
        val latestSlot = rx.maxOf { it.id / 1000 }
        return rx
            .filter { it.id / 1000 == latestSlot }
            .map { row ->
                SpectrumMarker(
                    freqHz = row.freqHz,
                    callsign = callsignOf(row.message),
                    isCq = row.isCq,
                )
            }
    }

    fun txClashes(
        txFreqHz: Int,
        markers: List<SpectrumMarker>,
        windowHz: Int = CLASH_WINDOW_HZ,
    ): Boolean = markers.any { abs(it.freqHz - txFreqHz) <= windowHz }

    private fun callsignOf(message: String): String =
        when (val rx = QsoMessages.parse(message)) {
            is QsoRx.Cq -> rx.call
            is QsoRx.GridReply -> rx.sender
            is QsoRx.Report -> rx.sender
            is QsoRx.RReport -> rx.sender
            is QsoRx.Roger -> rx.sender
            is QsoRx.RogerBye -> rx.sender
            is QsoRx.Bye -> rx.sender
            QsoRx.Other -> ""
        }
}
