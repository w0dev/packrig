package net.ft8vc.core

/** Whether a callsign has been worked on the current band, another band, or never. */
enum class WorkedBefore {
    Never,
    ThisBand,
    OtherBand,
    ;

    companion object {
        fun classify(currentBand: String?, workedBands: Set<String>): WorkedBefore = when {
            currentBand == null -> Never
            currentBand in workedBands -> ThisBand
            workedBands.isNotEmpty() -> OtherBand
            else -> Never
        }
    }
}
