package net.ft8vc.app

import net.ft8vc.app.settings.PttPreference
import net.ft8vc.audio.AudioInputDevice
import net.ft8vc.core.AppInfo
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.QsoForm
import net.ft8vc.core.QsoTxStep
import net.ft8vc.core.TxSlotParity

data class DecodeRow(
    val timeUtc: String,
    val snr: Int,
    val dtSeconds: Float,
    val freqHz: Int,
    val message: String,
    val isCq: Boolean,
    val isToMe: Boolean = false,
    /** Great-circle km when message carries a 4-char grid; null otherwise. */
    val distanceKm: Int? = null,
    /** 0 = even slot (:00/:30), 1 = odd — UTC period when this decode was received. */
    val slotParity: Int = 0,
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
    val autoAnswerCqEnabled: Boolean = false,
    val answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    val maxUnansweredTxCycles: Int = 5,
    val txMessage: String = DEFAULT_TX_MESSAGE,
    val txFreqHz: Int = DEFAULT_TX_FREQ_HZ,
    /** Next message [QsoMachine] will send on a TX slot, when a QSO is active. */
    val nextTxMessage: String? = null,
    val txStatus: String? = null,
    val isTransmitting: Boolean = false,
    val pttReady: Boolean = false,
    val myCall: String = DEFAULT_MY_CALL,
    val myGrid: String = DEFAULT_MY_GRID,
    val qsoActive: Boolean = false,
    val qsoState: String? = null,
    val qsoDx: String? = null,
    /** Next TX line on Operate (auto-filled from QSO machine; user may override). */
    val operateTxText: String = "",
    val operateTxStep: QsoTxStep = QsoTxStep.Cq,
    val operateTxEdited: Boolean = false,
    /** DX/reports synced from [QsoMachine] for TX dropdown previews. */
    val operateTxForm: QsoForm = QsoForm(),
    val qsoCompleteBanner: String? = null,
    val catReady: Boolean = false,
    val rigFreqHz: Long? = null,
    val rigMode: String? = null,
    val catStatus: String? = null,
    val catBusy: Boolean = false,
    val slotIndex: Int = 0,
    val secondsToNextSlot: Int = 15,
    val isTxSlot: Boolean = false,
    /** Seconds until the next slot matching [txSlotParity] (or active QSO parity). */
    val secondsUntilOurTxSlot: Int = 15,
    val txSlotParity: TxSlotParity = TxSlotParity.EVEN,
    val activeTxSlotParity: TxSlotParity? = null,
    val utcClock: String = "00:00:00",
    val operateStatus: String? = null,
    val contactCount: Int = 0,
    val snackbarMessage: String? = null,
    val waterfallBrightness: Float = 0.6f,
    val inputGain: Float = 1f,
    /** Operate-screen only: decode list shows CQ / 73 / RR73 (plus QSO partner when active). */
    val cq73OnlyFilter: Boolean = false,
    val decodeViewMode: DecodeViewMode = DecodeViewMode.OPERATE,
    val potaModeEnabled: Boolean = false,
    val potaParkRef: String = "",
    val pttPreference: PttPreference = PttPreference.AUTO,
    val lastDialFreqHz: Long? = null,
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
