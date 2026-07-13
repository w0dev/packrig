package net.packrig.core

/** How to pick a station when multiple CQ answers or resume opportunities appear in one slot. */
enum class AnswerPolicy {
    /** First matching decode in slot order. */
    FIRST,

    /** Highest decoded SNR. */
    BEST_SNR,

    /** Greatest Maidenhead grid distance from your grid (4-char locators). */
    FURTHEST,
}
