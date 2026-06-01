package net.ft8vc.core

/**
 * Pure helpers for FT8's 15-second slot grid, aligned to UTC.
 *
 * FT8 transmissions start at 0/15/30/45 seconds past the minute. Accurate slot
 * alignment is the single biggest factor in mobile decode reliability, so this
 * logic is kept pure and unit-tested rather than buried in Android timers.
 */
object SlotTiming {

    const val SLOT_MS: Long = AppInfo.SLOT_SECONDS * 1000L

    /** Index of the slot (0..3) within the current minute for the given epoch ms. */
    fun slotIndexInMinute(epochMillisUtc: Long): Int {
        val msIntoMinute = Math.floorMod(epochMillisUtc, 60_000L)
        return (msIntoMinute / SLOT_MS).toInt()
    }

    /** Epoch ms of the start of the slot containing [epochMillisUtc]. */
    fun slotStart(epochMillisUtc: Long): Long {
        return epochMillisUtc - Math.floorMod(epochMillisUtc, SLOT_MS)
    }

    /** Epoch ms of the next slot boundary strictly after [epochMillisUtc]. */
    fun nextSlotStart(epochMillisUtc: Long): Long {
        return slotStart(epochMillisUtc) + SLOT_MS
    }

    /** Milliseconds remaining until the next slot boundary. */
    fun millisUntilNextSlot(epochMillisUtc: Long): Long {
        return nextSlotStart(epochMillisUtc) - epochMillisUtc
    }
}
