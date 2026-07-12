package net.ft8vc.app.settings

import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity
import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigController
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry

/** PTT keying strategy for the rig's serial link. */
enum class PttPreference(val displayName: String, val description: String) {
    AUTO("Auto", "CAT if the rig answers, RTS fallback otherwise"),
    CAT("CAT command", "Software keying over serial — rig must respond to CAT"),
    RTS("RTS only", "Hardware keying via the serial RTS line"),
}

/** Map a descriptor's default PTT method onto the app's PTT preference. */
fun PttMethod.toPreference(): PttPreference = when (this) {
    PttMethod.AUTO -> PttPreference.AUTO
    PttMethod.CAT -> PttPreference.CAT
    PttMethod.RTS -> PttPreference.RTS
}

fun PttPreference.toPttMethod(): PttMethod = when (this) {
    PttPreference.AUTO -> PttMethod.AUTO
    PttPreference.CAT -> PttMethod.CAT
    PttPreference.RTS -> PttMethod.RTS
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
    /** Saved rig profiles (spec 2026-07-10-rig-profiles-design). Max 5 — see RigProfileList. */
    val rigProfiles: List<RigProfile> = emptyList(),
    /** Selected profile id; null until the operator adds/selects a rig. */
    val selectedRigProfileId: String? = null,
    val licenseAcknowledged: Boolean = false,
    val txEnabledInSettings: Boolean = false,
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val autoAnswerCqEnabled: Boolean = false,
    val lateStartTxEnabled: Boolean = true,
    val earlyDecodeEnabled: Boolean = true,
    /** Auto-start RX monitor (waterfall + decodes) when a radio's USB audio is connected (spec 2026-07-11-auto-rx-monitor-design). */
    val autoMonitorEnabled: Boolean = true,
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
    /** Auto-upload logged QSOs to QRZ.com (spec 2026-07-11-qrz-logbook-upload). */
    val qrzUploadEnabled: Boolean = false,
    /** Base64(iv+ciphertext) of the QRZ API key, AndroidKeyStore-encrypted; null = not set. */
    val qrzApiKeyEncrypted: String? = null,
    /** Last QRZ upload/test failure; persisted so the Settings warning survives restart. */
    val qrzLastError: String? = null,
) {
    val selectedRigProfile: RigProfile? get() = rigProfiles.firstOrNull { it.id == selectedRigProfileId }
}

/**
 * Project the selected rig profile onto the legacy rig fields so every
 * downstream consumer (OperateViewModel mirrors, RigController) is untouched:
 * radioModelId = the profile's preset, catBaud/catPortOverride/pttPreference =
 * profile knobs with preset-default fallback. No selection = passthrough
 * (pre-migration behavior).
 */
fun StationSettings.withRigProfileApplied(): StationSettings {
    val profile = selectedRigProfile ?: return this
    val preset = RigRegistry.byId(profile.presetId)
    return copy(
        radioModelId = profile.presetId,
        catBaud = SettingsRepository.coerceCatBaud(
            profile.baud ?: preset?.defaultBaud ?: catBaud,
        ),
        catPortOverride = profile.catPortIndex,
        pttPreference = (profile.pttMethod ?: preset?.defaultPtt)?.toPreference() ?: pttPreference,
    )
}
