package net.packrig.core

/**
 * Visual category of a decode row, in fixed priority order (first match wins).
 * Resolved by [DecodeCategoryResolver]; drives both the row color treatment
 * and the [DecodePrefix] glyph so the two can never disagree.
 */
enum class DecodeCategory {
    /** A row synthesized from our own transmission. */
    OWN_TX,

    /** Mentions the current QSO partner during an active QSO. */
    PARTNER,

    /** CQ from a station never worked. */
    CQ_NEW,

    /** CQ from a station worked before, but not on the current band. */
    CQ_WORKED_OTHER_BAND,

    /** CQ from a station already worked on the current band. */
    CQ_WORKED_THIS_BAND,

    /** Directed to my callsign — in or out of a QSO (tail-enders included). */
    MY_CALL,

    /** Everything else (band chatter). */
    OTHER,
}
