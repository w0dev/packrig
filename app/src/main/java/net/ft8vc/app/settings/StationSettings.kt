package net.ft8vc.app.settings

import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity
import net.ft8vc.rig.RigController

/** PTT keying strategy for the Digirig serial port. */
enum class PttPreference(val displayName: String, val description: String) {
    AUTO("Auto", "CAT if the rig answers, RTS fallback otherwise"),
    CAT("CAT (TX1/TX0)", "Software keying over serial — rig must respond to CAT"),
    RTS("RTS only", "Hardware keying via the Digirig serial RTS line"),
}

/** Persisted station and operating preferences. */
data class StationSettings(
    val myCall: String = "",
    val myGrid: String = "",
    val txToneHz: Int = 1000,
    val selectedAudioDeviceId: Int? = null,
    val pttPreference: PttPreference = PttPreference.AUTO,
    /** CAT serial baud — must match FT-891 menu 05-06 (CAT RATE). Default = v1.0 behavior. */
    val catBaud: Int = RigController.DEFAULT_CAT_BAUD,
    /** Selected radio model id (see net.ft8vc.rig.RigRegistry). Null = none chosen. */
    val radioModelId: String? = null,
    /** Operator override for the CAT serial port index; null = descriptor default. */
    val catPortOverride: Int? = null,
    val licenseAcknowledged: Boolean = false,
    val txEnabledInSettings: Boolean = false,
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val autoAnswerCqEnabled: Boolean = false,
    val lateStartTxEnabled: Boolean = true,
    val earlyDecodeEnabled: Boolean = true,
    /** Show CQ decode labels on the Spectrum waterfall. Default ON. */
    val spectrumMarkersEnabled: Boolean = true,
    val sendRr73: Boolean = true,
    val autoCqResumeEnabled: Boolean = false,
    val answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    /** 0 = no limit; abandon QSO after this many TX cycles without decode progress. */
    val maxUnansweredTxCycles: Int = 5,
    /** RX PCM scale 0.1–1.0; attenuate when the level meter clips. */
    val inputGain: Float = 1f,
    val lastDialFreqHz: Long? = null,
    val potaModeEnabled: Boolean = false,
    val potaParkRef: String = "",
    val cq73OnlyFilter: Boolean = false,
    val decodeViewMode: DecodeViewMode = DecodeViewMode.OPERATE,
    /** Transmit on even (:00/:30) or odd (:15/:45) UTC slots when calling CQ. */
    val txSlotParity: TxSlotParity = TxSlotParity.EVEN,
    /** Render the entire UI in the dark color scheme. Defaults to true (field-first). */
    val useDarkTheme: Boolean = true,
    /** User-configurable decode row colors (spec 2026-07-04-decode-colorscheme). */
    val decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,
    /** Phase 7: epoch ms of the most recent successful ADIF auto-backup, or null if none yet. */
    val lastAdifBackupAtMs: Long? = null,
)
