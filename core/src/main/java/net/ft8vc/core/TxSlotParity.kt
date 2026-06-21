package net.ft8vc.core

/** Which 15-second periods we transmit on (slots 0/2 = even, 1/3 = odd). */
enum class TxSlotParity(val bit: Int) {
    EVEN(0),
    ODD(1),
    ;

    val label: String get() = if (this == EVEN) "Even" else "Odd"

    /** UTC second hint within the minute: :00/:30 vs :15/:45. */
    val utcHint: String get() = if (this == EVEN) ":00/:30" else ":15/:45"

    companion object {
        fun fromBit(bit: Int): TxSlotParity = if (bit == 0) EVEN else ODD
    }
}

object TxSlotSelection {

    fun slotParity(epochMillisUtc: Long): Int =
        SlotTiming.slotIndexInMinute(epochMillisUtc) % 2

    /** Parity for calling CQ — follows the operator's Even/Odd preference. */
    fun parityForCallingCq(preference: TxSlotParity): Int = preference.bit

    /** Answer on the opposite period from the slot where the CQ/report was heard. */
    fun answerParity(hearingSlotParity: Int): Int = (hearingSlotParity + 1) % 2

    fun isTxSlot(epochMillisUtc: Long, txParity: Int): Boolean =
        slotParity(epochMillisUtc) == txParity

    /** Milliseconds until the start of the next slot matching [txParity]. */
    fun millisUntilNextTxSlot(epochMillisUtc: Long, txParity: Int): Long {
        var next = SlotTiming.nextSlotStart(epochMillisUtc)
        while (slotParity(next) != txParity) {
            next += SlotTiming.SLOT_MS
        }
        return next - epochMillisUtc
    }
}
