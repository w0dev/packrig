package net.ft8vc.rig

/**
 * Yaesu "new CAT" family specs beside [YaesuCat.FT891]. Every current-gen Yaesu
 * shares the same `MD0x` mode table and DATA-U (`C`) data mode; only the tuning
 * range varies. Ranges below are the published general-coverage RX/TX spans from
 * each rig's operating manual — verify the exact min/max against the manual when
 * touching a spec. The min is the receiver's low edge; the max covers the rig's
 * top band (HF+6 m for the FTDX10/FT-710/FTDX101/FTX-1; +VHF/UHF for the FT-991A).
 */
object YaesuModels {

    /** Shared new-CAT MD0x → label map (identical across the current family). */
    private val NEW_CAT_MODES: Map<Char, String> = YaesuCat.FT891.modeLabels

    /** FT-991A: HF/50/144/430 MHz all-mode. Manual: general coverage RX to 470 MHz. */
    val FT991A = YaesuModelSpec(
        name = "Yaesu FT-991A",
        minFreqHz = 30_000L,
        maxFreqHz = 470_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FTDX10: HF + 50 MHz. */
    val FTDX10 = YaesuModelSpec(
        name = "Yaesu FTDX10",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FT-710: HF + 50 MHz. */
    val FT710 = YaesuModelSpec(
        name = "Yaesu FT-710",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FTDX101 (D/MP): HF + 50 MHz. */
    val FTDX101 = YaesuModelSpec(
        name = "Yaesu FTDX101",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )

    /** FTX-1: HF + 50 MHz (portable SDR). Transport details unverified — see registry. */
    val FTX1 = YaesuModelSpec(
        name = "Yaesu FTX-1",
        minFreqHz = 30_000L,
        maxFreqHz = 56_000_000L,
        dataModeCode = 'C',
        modeLabels = NEW_CAT_MODES,
    )
}
