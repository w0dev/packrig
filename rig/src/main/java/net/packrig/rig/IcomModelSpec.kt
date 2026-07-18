package net.packrig.rig

/** How a CI-V model selects its FT8 data mode — varies by rig generation. */
enum class DataModeStrategy {
    /** One-shot 0x26: mode + data flag + filter (IC-7300 era and newer). */
    CMD_26,

    /** 0x06 mode set followed by the 0x1A 0x06 data-mode subcommand (IC-7100 era). */
    CMD_06_PLUS_1A,

    /** Plain USB via 0x06 — the rig has no data mode at all (Xiegu G90). */
    CMD_06_ONLY,
}

/**
 * Per-model CI-V parameters: bus address, tuning bounds, and the data-mode
 * command generation. Values authored from CAT manuals and cross-checked
 * against hamlib + FT8CN (see docs/RIG_MODELS.md).
 */
data class IcomModelSpec(
    val name: String,
    val civAddress: Int,
    val minFreqHz: Long,
    val maxFreqHz: Long,
    val dataModeStrategy: DataModeStrategy,
    val modeLabels: Map<Int, String> = CIV_MODE_LABELS,
) {
    companion object {
        /** Standard CI-V mode codes (command 0x04 reply / 0x01 broadcast). */
        val CIV_MODE_LABELS: Map<Int, String> = mapOf(
            0x00 to "LSB", 0x01 to "USB", 0x02 to "AM", 0x03 to "CW",
            0x04 to "RTTY", 0x05 to "FM", 0x06 to "WFM", 0x07 to "CW-R",
            0x08 to "RTTY-R", 0x17 to "DV",
        )
    }
}
