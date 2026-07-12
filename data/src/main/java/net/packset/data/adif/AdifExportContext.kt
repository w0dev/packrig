package net.packset.data.adif

/** Context applied when exporting contacts to ADIF. */
data class AdifExportContext(
    val programId: String = "Packset",
    val programVersion: String = "1.0.0",
    val adifVersion: String = "3.1.4",
    /** When set (single-activation export), every record is stamped with this one park. */
    val activationParkRef: String? = null,
)

class AdifExportException(message: String) : IllegalStateException(message)
