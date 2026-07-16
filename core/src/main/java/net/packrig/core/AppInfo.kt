package net.packrig.core

/** Development phases tracked in the project plan. */
enum class BuildPhase(val label: String) {
    PHASE_0_SKELETON("Phase 0: skeleton"),
    PHASE_1_AUDIO("Phase 1: USB audio + waterfall"),
    PHASE_2_DECODE("Phase 2: decode core"),
    PHASE_3_TX("Phase 3: TX + PTT"),
    PHASE_4_UX("Phase 4: operating UI"),
    PHASE_5_RELEASE("Phase 5: logging + release"),
}

/** App-wide constants surfaced in the UI and logs. */
object AppInfo {
    const val APP_NAME = "PackRig"
    const val TAGLINE = "FT8, vibe-coded"

    /** FT8 internal audio sample rate. Everything DSP runs at this rate. */
    const val SAMPLE_RATE_HZ = 12_000

    /** FT8 transmit/receive slot length. */
    const val SLOT_SECONDS = 15

    val currentPhase: BuildPhase = BuildPhase.PHASE_5_RELEASE
}
