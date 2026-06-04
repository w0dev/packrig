package net.ft8vc.app

import net.ft8vc.audio.AudioInputDevice
import net.ft8vc.core.AppInfo

data class DecodeRow(
    val timeUtc: String,
    val snr: Int,
    val dtSeconds: Float,
    val freqHz: Int,
    val message: String,
    val isCq: Boolean,
    val isToMe: Boolean = false,
)

data class OperateUiState(
    val devices: List<AudioInputDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val isOperating: Boolean = false,
    val isCapturing: Boolean = false,
    val levelDbfs: Float = SILENCE_DBFS,
    val clip: Boolean = false,
    val sampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ,
    val error: String? = null,
    val waterfallVersion: Long = 0L,
    val decodes: List<DecodeRow> = emptyList(),
    val lastSlotDecodeCount: Int = -1,
    val licenseAcknowledged: Boolean = false,
    val txEnabled: Boolean = false,
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val txMessage: String = DEFAULT_TX_MESSAGE,
    val txFreqHz: Int = DEFAULT_TX_FREQ_HZ,
    val txStatus: String? = null,
    val isTransmitting: Boolean = false,
    val pttReady: Boolean = false,
    val myCall: String = DEFAULT_MY_CALL,
    val myGrid: String = DEFAULT_MY_GRID,
    val qsoActive: Boolean = false,
    val qsoState: String? = null,
    val qsoDx: String? = null,
    val qsoCompleteBanner: String? = null,
    val catReady: Boolean = false,
    val rigFreqHz: Long? = null,
    val rigMode: String? = null,
    val catStatus: String? = null,
    val catBusy: Boolean = false,
    val slotIndex: Int = 0,
    val secondsToNextSlot: Int = 15,
    val isTxSlot: Boolean = false,
    val utcClock: String = "00:00:00",
    val operateStatus: String? = null,
    val contactCount: Int = 0,
    val snackbarMessage: String? = null,
    val waterfallBrightness: Float = 0.6f,
    val inputGain: Float = 1f,
    /** Operate-screen only: decode list shows CQ / 73 / RR73 (plus QSO partner when active). */
    val cq73OnlyFilter: Boolean = false,
) {
    companion object {
        const val INPUT_GAIN_MIN = 0.1f
        const val SILENCE_DBFS = -100f
        const val MAX_DECODE_ROWS = 300
        const val DEFAULT_TX_MESSAGE = "CQ TEST FN31"
        const val DEFAULT_TX_FREQ_HZ = 1000
        const val DEFAULT_MY_CALL = "TEST"
        const val DEFAULT_MY_GRID = "FN31"
        const val QSO_TX_GRACE_MS = 300L
    }
}
