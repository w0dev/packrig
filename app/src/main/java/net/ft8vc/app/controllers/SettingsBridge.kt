package net.ft8vc.app.controllers

import net.ft8vc.app.settings.PttPreference
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.app.settings.StationSettings
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity
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
        licenseAcknowledged = licenseAcknowledged,
        txEnabledInSettings = txEnabledInSettings,
        lateStartTxEnabled = lateStartTxEnabled,
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
        decodeViewMode = decodeViewMode,
        txSlotParity = txSlotParity,
        useDarkTheme = useDarkTheme,
    )
}

/** Snapshot of all settings fields consumed by [net.ft8vc.app.OperateViewModel]. */
data class SettingsSlice(
    val myCall: String = "",
    val myGrid: String = "",
    val txToneHz: Int = 1000,
    val selectedAudioDeviceId: Int? = null,
    val pttPreference: PttPreference = PttPreference.AUTO,
    val licenseAcknowledged: Boolean = false,
    val txEnabledInSettings: Boolean = false,
    val lateStartTxEnabled: Boolean = true,
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
    val decodeViewMode: DecodeViewMode = DecodeViewMode.OPERATE,
    val txSlotParity: TxSlotParity = TxSlotParity.EVEN,
    val useDarkTheme: Boolean = true,
    val lastAdifBackupAtMs: Long? = null,
)
