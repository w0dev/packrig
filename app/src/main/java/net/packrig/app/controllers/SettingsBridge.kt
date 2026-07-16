package net.packrig.app.controllers

import net.packrig.app.settings.DecodeColorScheme
import net.packrig.app.settings.PttPreference
import net.packrig.app.settings.SettingsRepository
import net.packrig.app.settings.StationSettings
import net.packrig.core.AnswerPolicy
import net.packrig.core.DecodeViewMode
import net.packrig.core.TxSlotParity
import net.packrig.rig.RigController
import net.packrig.rig.RigProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Observes [SettingsRepository.settings] and maps it onto [SettingsSlice].
 *
 * Emits [stationIdentityChanged] whenever myCall, myGrid, or potaModeEnabled changes,
 * so callers can react without re-deriving the identity comparison themselves.
 */
class SettingsBridge(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _slice = MutableStateFlow(SettingsSlice())
    val slice: StateFlow<SettingsSlice> = _slice.asStateFlow()

    private val _stationIdentityChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stationIdentityChanged: SharedFlow<Unit> = _stationIdentityChanged.asSharedFlow()

    init {
        scope.launch {
            repo.settings.collect { s ->
                val prev = _slice.value
                _slice.value = s.toSlice()
                if (identityChanged(prev, s)) {
                    _stationIdentityChanged.tryEmit(Unit)
                }
            }
        }
    }

    private fun identityChanged(prev: SettingsSlice, s: StationSettings): Boolean =
        prev.myCall != s.myCall ||
            prev.myGrid != s.myGrid ||
            prev.potaModeEnabled != s.potaModeEnabled

    private fun StationSettings.toSlice() = SettingsSlice(
        lastAdifBackupAtMs = lastAdifBackupAtMs,
        myCall = myCall,
        myGrid = myGrid,
        txToneHz = txToneHz,
        selectedAudioDeviceId = selectedAudioDeviceId,
        pttPreference = pttPreference,
        catBaud = catBaud,
        radioModelId = radioModelId,
        catPortOverride = catPortOverride,
        rigProfiles = rigProfiles,
        selectedRigProfileId = selectedRigProfileId,
        licenseAcknowledged = licenseAcknowledged,
        txEnabledInSettings = txEnabledInSettings,
        lateStartTxEnabled = lateStartTxEnabled,
        earlyDecodeEnabled = earlyDecodeEnabled,
        sendRr73 = sendRr73,
        autoCqResumeEnabled = autoCqResumeEnabled,
        autoSeqEnabled = autoSeqEnabled,
        answerWhenCalledEnabled = answerWhenCalledEnabled,
        autoAnswerCqEnabled = autoAnswerCqEnabled,
        answerPolicy = answerPolicy,
        maxUnansweredTxCycles = maxUnansweredTxCycles,
        inputGain = inputGain,
        lastDialFreqHz = lastDialFreqHz,
        potaModeEnabled = potaModeEnabled,
        potaParkRef = potaParkRef,
        cq73OnlyFilter = cq73OnlyFilter,
        blockConfirmEnabled = blockConfirmEnabled,
        decodeViewMode = decodeViewMode,
        txSlotParity = txSlotParity,
        useDarkTheme = useDarkTheme,
        decodeColors = decodeColors,
    )
}

/** Snapshot of all settings fields consumed by [net.packrig.app.OperateViewModel]. */
data class SettingsSlice(
    val myCall: String = "",
    val myGrid: String = "",
    val txToneHz: Int = 1000,
    val selectedAudioDeviceId: Int? = null,
    val pttPreference: PttPreference = PttPreference.AUTO,
    val catBaud: Int = RigController.DEFAULT_CAT_BAUD,
    val radioModelId: String? = null,
    val catPortOverride: Int? = null,
    val rigProfiles: List<RigProfile> = emptyList(),
    val selectedRigProfileId: String? = null,
    val licenseAcknowledged: Boolean = false,
    val txEnabledInSettings: Boolean = false,
    val lateStartTxEnabled: Boolean = true,
    val earlyDecodeEnabled: Boolean = true,
    val sendRr73: Boolean = true,
    val autoCqResumeEnabled: Boolean = false,
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val autoAnswerCqEnabled: Boolean = false,
    val answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    val maxUnansweredTxCycles: Int = 5,
    val inputGain: Float = 1f,
    val lastDialFreqHz: Long? = null,
    val potaModeEnabled: Boolean = false,
    val potaParkRef: String = "",
    val cq73OnlyFilter: Boolean = false,
    val blockConfirmEnabled: Boolean = true,
    val decodeViewMode: DecodeViewMode = DecodeViewMode.OPERATE,
    val txSlotParity: TxSlotParity = TxSlotParity.EVEN,
    val useDarkTheme: Boolean = true,
    val decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,
    val lastAdifBackupAtMs: Long? = null,
)
