package net.ft8vc.data.adif

/** Context applied when exporting contacts to ADIF. */
data class AdifExportContext(
    val programId: String = "FT8VC",
    val programVersion: String = "1.0.0",
    val adifVersion: String = "3.1.4",
    val potaEnabled: Boolean = false,
    val potaParkRef: String? = null,
)

class AdifExportException(message: String) : IllegalStateException(message)
