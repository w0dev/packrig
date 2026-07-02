package net.ft8vc.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ft8vc_settings",
)

class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    val settings: Flow<StationSettings> = appContext.settingsDataStore.data.map { prefs ->
        StationSettings(
            myCall = prefs[Keys.MY_CALL] ?: "",
            myGrid = prefs[Keys.MY_GRID] ?: "",
            txToneHz = prefs[Keys.TX_TONE_HZ] ?: 1000,
            selectedAudioDeviceId = prefs[Keys.AUDIO_DEVICE_ID],
            pttPreference = prefs[Keys.PTT_PREFERENCE]?.let { PttPreference.valueOf(it) }
                ?: PttPreference.AUTO,
            licenseAcknowledged = prefs[Keys.LICENSE_ACK] ?: false,
            txEnabledInSettings = prefs[Keys.TX_ENABLED] ?: false,
            autoSeqEnabled = prefs[Keys.AUTO_SEQ] ?: true,
            answerWhenCalledEnabled = prefs[Keys.ANSWER_WHEN_CALLED] ?: true,
            autoAnswerCqEnabled = prefs[Keys.AUTO_ANSWER_CQ] ?: false,
            lateStartTxEnabled = prefs[Keys.LATE_START_TX_ENABLED] ?: true,
            earlyDecodeEnabled = prefs[Keys.EARLY_DECODE_ENABLED] ?: true,
            sendRr73 = prefs[Keys.SEND_RR73] ?: true,
            answerPolicy = prefs[Keys.ANSWER_POLICY]?.let { AnswerPolicy.valueOf(it) }
                ?: AnswerPolicy.FIRST,
            maxUnansweredTxCycles = prefs[Keys.MAX_UNANSWERED_TX] ?: 5,
            inputGain = prefs[Keys.INPUT_GAIN] ?: 1f,
            lastDialFreqHz = prefs[Keys.LAST_DIAL_FREQ_HZ],
            potaModeEnabled = prefs[Keys.POTA_MODE] ?: false,
            potaParkRef = prefs[Keys.POTA_PARK_REF] ?: "",
            cq73OnlyFilter = prefs[Keys.CQ73_FILTER] ?: false,
            decodeViewMode = prefs[Keys.DECODE_VIEW_MODE]?.let { DecodeViewMode.valueOf(it) }
                ?: DecodeViewMode.OPERATE,
            txSlotParity = prefs[Keys.TX_SLOT_PARITY]?.let { TxSlotParity.valueOf(it) }
                ?: TxSlotParity.EVEN,
            useDarkTheme = prefs[Keys.USE_DARK_THEME] ?: true,
            lastAdifBackupAtMs = prefs[Keys.LAST_ADIF_BACKUP_AT_MS],
        )
    }

    suspend fun setMyCall(call: String) {
        appContext.settingsDataStore.edit { it[Keys.MY_CALL] = call.trim().uppercase() }
    }

    suspend fun setMyGrid(grid: String) {
        appContext.settingsDataStore.edit { it[Keys.MY_GRID] = grid.trim().uppercase() }
    }

    suspend fun setTxToneHz(hz: Int) {
        appContext.settingsDataStore.edit { it[Keys.TX_TONE_HZ] = hz.coerceIn(300, 3000) }
    }

    suspend fun setSelectedAudioDeviceId(id: Int?) {
        appContext.settingsDataStore.edit {
            if (id == null) it.remove(Keys.AUDIO_DEVICE_ID) else it[Keys.AUDIO_DEVICE_ID] = id
        }
    }

    suspend fun setPttPreference(pref: PttPreference) {
        appContext.settingsDataStore.edit { it[Keys.PTT_PREFERENCE] = pref.name }
    }

    suspend fun setLicenseAcknowledged(ack: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.LICENSE_ACK] = ack }
    }

    suspend fun setTxEnabledInSettings(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.TX_ENABLED] = enabled }
    }

    suspend fun setAutoSeqEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.AUTO_SEQ] = enabled }
    }

    suspend fun setAnswerWhenCalledEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.ANSWER_WHEN_CALLED] = enabled }
    }

    suspend fun setAutoAnswerCqEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.AUTO_ANSWER_CQ] = enabled }
    }

    suspend fun setLateStartTxEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.LATE_START_TX_ENABLED] = enabled }
    }

    suspend fun setEarlyDecodeEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.EARLY_DECODE_ENABLED] = enabled }
    }

    suspend fun setSendRr73(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.SEND_RR73] = enabled }
    }

    suspend fun setAnswerPolicy(policy: AnswerPolicy) {
        appContext.settingsDataStore.edit { it[Keys.ANSWER_POLICY] = policy.name }
    }

    suspend fun setMaxUnansweredTxCycles(cycles: Int) {
        appContext.settingsDataStore.edit {
            it[Keys.MAX_UNANSWERED_TX] = cycles.coerceIn(0, 20)
        }
    }

    suspend fun setInputGain(value: Float) {
        appContext.settingsDataStore.edit {
            it[Keys.INPUT_GAIN] = value.coerceIn(INPUT_GAIN_MIN, 1f)
        }
    }

    suspend fun setLastDialFreqHz(hz: Long?) {
        appContext.settingsDataStore.edit {
            if (hz == null) it.remove(Keys.LAST_DIAL_FREQ_HZ) else it[Keys.LAST_DIAL_FREQ_HZ] = hz
        }
    }

    suspend fun setPotaModeEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.POTA_MODE] = enabled }
    }

    suspend fun setPotaParkRef(ref: String) {
        appContext.settingsDataStore.edit { it[Keys.POTA_PARK_REF] = ref.trim().uppercase() }
    }

    suspend fun setCq73OnlyFilter(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.CQ73_FILTER] = enabled }
    }

    suspend fun setDecodeViewMode(mode: DecodeViewMode) {
        appContext.settingsDataStore.edit { it[Keys.DECODE_VIEW_MODE] = mode.name }
    }

    suspend fun setTxSlotParity(parity: TxSlotParity) {
        appContext.settingsDataStore.edit { it[Keys.TX_SLOT_PARITY] = parity.name }
    }

    suspend fun setUseDarkTheme(value: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.USE_DARK_THEME] = value }
    }

    /** Phase 7 (UX-06, HYG-04): epoch ms of the most recent successful ADIF auto-backup. */
    suspend fun setLastAdifBackupAtMs(value: Long) {
        appContext.settingsDataStore.edit { it[Keys.LAST_ADIF_BACKUP_AT_MS] = value }
    }

    private object Keys {
        val MY_CALL = stringPreferencesKey("my_call")
        val MY_GRID = stringPreferencesKey("my_grid")
        val TX_TONE_HZ = intPreferencesKey("tx_tone_hz")
        val AUDIO_DEVICE_ID = intPreferencesKey("audio_device_id")
        val PTT_PREFERENCE = stringPreferencesKey("ptt_preference")
        val LICENSE_ACK = booleanPreferencesKey("license_ack")
        val TX_ENABLED = booleanPreferencesKey("tx_enabled")
        val AUTO_SEQ = booleanPreferencesKey("auto_seq")
        val ANSWER_WHEN_CALLED = booleanPreferencesKey("answer_when_called")
        val AUTO_ANSWER_CQ = booleanPreferencesKey("auto_answer_cq")
        val LATE_START_TX_ENABLED = booleanPreferencesKey("late_start_tx_enabled")
        val EARLY_DECODE_ENABLED = booleanPreferencesKey("early_decode_enabled")
        val SEND_RR73 = booleanPreferencesKey("send_rr73")
        val ANSWER_POLICY = stringPreferencesKey("answer_policy")
        val MAX_UNANSWERED_TX = intPreferencesKey("max_unanswered_tx")
        val INPUT_GAIN = floatPreferencesKey("input_gain")
        val LAST_DIAL_FREQ_HZ = longPreferencesKey("last_dial_freq_hz")
        val POTA_MODE = booleanPreferencesKey("pota_mode")
        val POTA_PARK_REF = stringPreferencesKey("pota_park_ref")
        val CQ73_FILTER = booleanPreferencesKey("cq73_filter")
        val DECODE_VIEW_MODE = stringPreferencesKey("decode_view_mode")
        val TX_SLOT_PARITY = stringPreferencesKey("tx_slot_parity")
        val USE_DARK_THEME = booleanPreferencesKey("use_dark_theme")
        val LAST_ADIF_BACKUP_AT_MS = longPreferencesKey("last_adif_backup_at_ms")
    }

    companion object {
        const val INPUT_GAIN_MIN = 0.1f
    }
}
