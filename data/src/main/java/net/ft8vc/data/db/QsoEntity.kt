package net.ft8vc.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
)
