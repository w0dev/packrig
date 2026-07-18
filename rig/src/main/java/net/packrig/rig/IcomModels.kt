package net.packrig.rig

/**
 * CI-V model table (Phase 4 flagship presets + the generic entry). Every value
 * cross-checked against ≥2 of hamlib / FT8CN / wfview on 2026-07-17; rows and
 * discrepancies tracked in docs/RIG_MODELS.md. All community-verification
 * tier — no CI-V reference hardware on the bench.
 */
object IcomModels {

    val IC7300 = IcomModelSpec(
        name = "Icom IC-7300",
        civAddress = 0x94,
        minFreqHz = 30_000L,
        maxFreqHz = 74_800_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )

    val IC705 = IcomModelSpec(
        name = "Icom IC-705",
        civAddress = 0xA4,
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )

    // Pre-0x26 generation: data mode via the 0x1A 0x06 subcommand (IC-7100
    // manual; hamlib's capability flags are ambiguous here — community
    // verification adjudicates).
    val IC7100 = IcomModelSpec(
        name = "Icom IC-7100",
        civAddress = 0x88,
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_06_PLUS_1A,
    )

    // No data mode at all — FT8 runs in plain USB (FT8CN does the same).
    // Address 0x70 per the Xiegu manual + Radioddity guide + FT8CN.
    val XIEGU_G90 = IcomModelSpec(
        name = "Xiegu G90",
        civAddress = 0x70,
        minFreqHz = 500_000L,
        maxFreqHz = 30_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_06_ONLY,
    )

    // Emulates the IC-705 command set (wfview: "identifies as IC-705").
    val XIEGU_X6100 = IcomModelSpec(
        name = "Xiegu X6100",
        civAddress = 0xA4,
        minFreqHz = 500_000L,
        maxFreqHz = 54_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )

    /** Backs the generic presets' "Icom CI-V" protocol choice: wide bounds,
     *  modern data command; the profile's address field supplies the address. */
    val GENERIC = IcomModelSpec(
        name = "Icom CI-V (generic)",
        civAddress = 0x94,
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeStrategy = DataModeStrategy.CMD_26,
    )
}
