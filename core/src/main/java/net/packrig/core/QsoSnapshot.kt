package net.packrig.core

/** Immutable snapshot of a completed QSO, suitable for logging. */
data class QsoSnapshot(
    val myCall: String,
    val myGrid: String,
    val dxCall: String,
    val dxGrid: String?,
    val reportSent: Int?,
    val reportRcvd: Int?,
    val role: QsoRole,
    val completedAtEpochMs: Long,
)
