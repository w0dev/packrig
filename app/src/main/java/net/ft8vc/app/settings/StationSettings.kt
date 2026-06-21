package net.ft8vc.app.settings

import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity

/** PTT keying strategy for the Digirig serial port. */
enum class PttPreference {
    AUTO,
    CAT,
    RTS,
}

/** Persisted station and operating preferences. */
data class StationSettings(
    val myCall: String = "TEST",
    val myGrid: String = "FN31",
    val txToneHz: Int = 1000,
    val selectedAudioDeviceId: Int? = null,
    val pttPreference: PttPreference = PttPreference.AUTO,
    val licenseAcknowledged: Boolean = false,
    val txEnabledInSettings: Boolean = false,
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val autoAnswerCqEnabled: Boolean = false,
    val answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    /** 0 = no limit; abandon QSO after this many TX cycles without decode progress. */
    val maxUnansweredTxCycles: Int = 5,
    val waterfallBrightness: Float = 0.6f,
    /** RX PCM scale 0.1–1.0; attenuate when the level meter clips. */
    val inputGain: Float = 1f,
    val lastDialFreqHz: Long? = null,
    val potaModeEnabled: Boolean = false,
    val potaParkRef: String = "",
    val cq73OnlyFilter: Boolean = false,
    val decodeViewMode: DecodeViewMode = DecodeViewMode.OPERATE,
    /** Transmit on even (:00/:30) or odd (:15/:45) UTC slots when calling CQ. */
    val txSlotParity: TxSlotParity = TxSlotParity.EVEN,
)
