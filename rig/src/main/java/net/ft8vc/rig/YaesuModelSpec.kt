package net.ft8vc.rig

/**
 * Per-model parameters for the Yaesu "new CAT" ASCII dialect ([YaesuCat]).
 * Adding a rig in this family is a new spec value plus tests — no new parser.
 */
data class YaesuModelSpec(
    val name: String,
    /** Lowest/highest VFO frequencies the rig accepts, in Hz. */
    val minFreqHz: Long,
    val maxFreqHz: Long,
    /** `MD0x` code of the FT8 data mode (DATA-USB). */
    val dataModeCode: Char,
    /** `MD0x` code → display label, for mode readback. */
    val modeLabels: Map<Char, String>,
)
