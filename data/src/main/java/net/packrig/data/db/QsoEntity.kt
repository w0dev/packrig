package net.packrig.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Values for [QsoEntity.qrzUploadState] (spec 2026-07-11-qrz-logbook-upload). */
object QrzUploadState {
    /** Logged before QRZ upload was enabled (or imported) — never uploaded. */
    const val NOT_QUEUED = "NOT_QUEUED"
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
}

@Entity(
    tableName = "qso_contacts",
    indices = [Index(value = ["dxCall", "utcMillis"], unique = true)],
)
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utcMillis: Long,
    val myCall: String,
    val myGrid: String,
    val dxCall: String,
    val dxGrid: String?,
    val rstSent: Int?,
    val rstRcvd: Int?,
    val freqHz: Long?,
    val mode: String,
    val band: String?,
    val notes: String,
    val potaParkRefs: String? = null,
    val qrzUploadState: String = QrzUploadState.NOT_QUEUED,
)
