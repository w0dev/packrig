package net.packrig.app.ui

import net.packrig.core.SlotTiming

/**
 * Tracks 15-second slot-boundary marker rows for a scrolling waterfall of
 * [history] rows. Each incoming column shifts the image (and therefore every
 * mark) up one row; the first column of a new UTC slot records a mark on the
 * bottom row carrying the new slot's start time.
 *
 * Pure logic with no Android imports so it stays JVM-testable: [Waterfall]
 * owns one but constructs a Bitmap and cannot be instantiated in unit tests.
 * Not thread-safe; [Waterfall] calls it under its own lock.
 */
class SlotMarkerTracker(private val history: Int) {

    data class SlotMark(val row: Int, val slotStartEpochMillis: Long)

    private val marks = ArrayDeque<SlotMark>()
    private var lastSlotStart: Long? = null

    /** A new column arrived at [epochMillisUtc]; advance the marks one row. */
    fun onColumn(epochMillisUtc: Long) {
        for (i in marks.indices) marks[i] = marks[i].copy(row = marks[i].row - 1)
        while (marks.isNotEmpty() && marks.first().row < 0) marks.removeFirst()

        val slotStart = SlotTiming.slotStart(epochMillisUtc)
        if (lastSlotStart != null && slotStart != lastSlotStart) {
            marks.addLast(SlotMark(history - 1, slotStart))
        }
        lastSlotStart = slotStart
    }

    fun markers(): List<SlotMark> = marks.toList()

    fun clear() {
        marks.clear()
        lastSlotStart = null
    }
}
