package net.ft8vc.data.model

import net.ft8vc.core.QsoSnapshot

/** Domain model for a logged FT8 contact. */
data class QsoContact(
    val id: Long = 0,
    val utcMillis: Long,
    val myCall: String,
    val myGrid: String,
    val dxCall: String,
    val dxGrid: String?,
    val rstSent: Int?,
    val rstRcvd: Int?,
    val freqHz: Long?,
    val mode: String = "FT8",
    val band: String?,
    val notes: String = "",
    /** Normalized comma-separated POTA park refs captured at QSO completion; null = home QSO. */
    val potaParkRefs: String? = null,
) {
    companion object {
        fun fromSnapshot(
            snapshot: QsoSnapshot,
            freqHz: Long?,
            band: String?,
            potaParkRefs: String? = null,
        ): QsoContact =
            QsoContact(
                utcMillis = snapshot.completedAtEpochMs,
                myCall = snapshot.myCall,
                myGrid = snapshot.myGrid,
                dxCall = snapshot.dxCall,
                dxGrid = snapshot.dxGrid,
                rstSent = snapshot.reportSent,
                rstRcvd = snapshot.reportRcvd,
                freqHz = freqHz,
                mode = "FT8",
                band = band,
                potaParkRefs = potaParkRefs,
            )
    }
}
