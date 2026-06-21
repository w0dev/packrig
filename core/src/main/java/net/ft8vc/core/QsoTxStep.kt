package net.ft8vc.core

/** User-facing TX step in the Operate message menu. Maps to [QsoState] via [QsoFormLogic]. */
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
