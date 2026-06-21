package net.ft8vc.core

import kotlin.math.roundToInt

/** Great-circle distance from decoded FT8 messages for the Operate decode list. */
object DecodeDistance {

    /** 4-char Maidenhead grid in a standard FT8 CQ or grid-reply message, if present. */
    fun gridFromMessage(message: String): String? = when (val rx = QsoMessages.parse(message)) {
        is QsoRx.Cq -> rx.grid?.uppercase()
        is QsoRx.GridReply -> rx.grid.uppercase()
        else -> null
    }

    /** Distance in km from [myGrid] to the grid in [message], or null when unknown. */
    fun kmFromMessage(myGrid: String, message: String): Int? {
        val dxGrid = gridFromMessage(message) ?: return null
        val km = MaidenheadGrid.distanceKm(myGrid, dxGrid) ?: return null
        return km.roundToInt().coerceIn(0, 9999)
    }

    /** Compact decode-list label, e.g. `1234` or `—`. */
    fun label(km: Int?): String = km?.let { "%4d".format(it) } ?: "   —"
}
