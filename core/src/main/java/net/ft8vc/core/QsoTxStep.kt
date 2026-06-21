package net.ft8vc.core

/** User-facing TX step (WSJT-X-style generate menu). Maps to [QsoState] via [QsoFormLogic]. */
enum class QsoTxStep(val label: String) {
    Idle("Idle"),
    Cq("CQ"),
    Grid("Grid reply"),
    Report("Report"),
    RReport("R+report"),
    Roger("RRR"),
    SeventyThree("73"),
    Custom("Custom"),
}
